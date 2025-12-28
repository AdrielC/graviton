package graviton.core.manifest

import graviton.core.codec.BinaryKeyCodec
import graviton.core.ranges.Span
import graviton.core.types.{ManifestAnnotationKey, ManifestAnnotationValue}
import graviton.core.types.Offset

import scodec.*
import scodec.bits.BitVector
import scodec.codecs.*

object FramedManifest:
  final case class Frame(bytes: Array[Byte])

  private val Version: Byte = 1

  // Hard bounds (P0): enforced in code to avoid OOM-by-valid-input.
  val MaxManifestEntries: Int     = 16384
  val MaxAnnotationsPerEntry: Int = 256

  private def combine[A, B](first: Codec[A], second: Codec[B]): Codec[(A, B)] =
    new Codec[(A, B)]:
      override def sizeBound: SizeBound = first.sizeBound + second.sizeBound

      override def encode(value: (A, B)): Attempt[BitVector] =
        for
          aBits <- first.encode(value._1)
          bBits <- second.encode(value._2)
        yield aBits ++ bBits

      override def decode(bits: BitVector): Attempt[DecodeResult[(A, B)]] =
        first.decode(bits).flatMap { case DecodeResult(a, remainder) =>
          second.decode(remainder).map { case DecodeResult(b, tail) => DecodeResult((a, b), tail) }
        }

  private val versionCodec: Codec[Unit] =
    uint8.exmap(
      {
        case v if v == Version => Attempt.successful(())
        case other             => Attempt.failure(Err(s"Unsupported manifest frame version $other (expected $Version)"))
      },
      _ => Attempt.successful(Version),
    )

  private def nonNegativeInt(context: String): Codec[Int] =
    int32.exmap(
      count =>
        if count >= 0 then Attempt.successful(count)
        else Attempt.failure(Err(s"$context cannot be negative: $count")),
      count => Attempt.successful(count),
    )

  private def nonNegativeLong(context: String): Codec[Long] =
    int64.exmap(
      value =>
        if value >= 0 then Attempt.successful(value)
        else Attempt.failure(Err(s"$context cannot be negative: $value")),
      value => Attempt.successful(value),
    )

  private val annotationsCodec: Codec[Map[ManifestAnnotationKey, ManifestAnnotationValue]] =
    listOfN(uint16, combine(variableSizeBytes(uint16, utf8), variableSizeBytes(uint16, utf8))).exmap(
      pairs =>
        if pairs.length > MaxAnnotationsPerEntry then
          Attempt.failure(Err(s"Too many annotations: ${pairs.length} (max $MaxAnnotationsPerEntry)"))
        else
          val builder                   = Map.newBuilder[ManifestAnnotationKey, ManifestAnnotationValue]
          val seen                      = scala.collection.mutable.HashSet.empty[String]
          var duplicate: Option[String] = None
          var invalid: Option[String]   = None

          pairs.foreach { case (key0, value0) =>
            val kTrim = Option(key0).getOrElse("").trim
            if invalid.isEmpty then
              (ManifestAnnotationKey.either(kTrim), ManifestAnnotationValue.either(Option(value0).getOrElse(""))) match
                case (Right(k), Right(v)) =>
                  if !seen.add(k.value) then duplicate = Some(k.value)
                  builder += k -> v
                case (Left(err), _)       => invalid = Some(s"Invalid annotation key '$kTrim': $err")
                case (_, Left(err))       => invalid = Some(s"Invalid annotation value for key '$kTrim': $err")
          }

          invalid match
            case Some(err) => Attempt.failure(Err(err))
            case None      =>
              duplicate match
                case Some(key) => Attempt.failure(Err(s"Duplicate key '$key' in annotations"))
                case None      => Attempt.successful(builder.result())
      ,
      map =>
        if map.size > MaxAnnotationsPerEntry then Attempt.failure(Err(s"Too many annotations: ${map.size} (max $MaxAnnotationsPerEntry)"))
        else Attempt.successful(map.toList.sortBy(_._1.value).map { case (k, v) => (k.value, v.value) }),
    )

  private val manifestEntryCodec: Codec[ManifestEntry] =
    combine(combine(combine(BinaryKeyCodec.codec, int64), int64), annotationsCodec).exmap(
      { case (((key, start), end), annotations) =>
        Attempt.fromEither(
          (for
            s    <- Offset.either(start).left.map(err => s"Invalid manifest start offset $start: $err")
            e    <- Offset.either(end).left.map(err => s"Invalid manifest end offset $end: $err")
            span <- Span.make(s, e)
          yield ManifestEntry(key, span, annotations)).left.map(Err(_))
        )
      },
      entry =>
        Attempt.successful(
          (
            ((entry.key, entry.span.startInclusive.value), entry.span.endInclusive.value),
            entry.annotations,
          )
        ),
    )

  private val entriesWithSizeCodec: Codec[(List[ManifestEntry], Long)] =
    combine(
      nonNegativeInt("entry count")
        .exmap(
          count =>
            if count <= MaxManifestEntries then Attempt.successful(count)
            else Attempt.failure(Err(s"Manifest has too many entries: $count (max $MaxManifestEntries)")),
          count =>
            if count <= MaxManifestEntries then Attempt.successful(count)
            else Attempt.failure(Err(s"Manifest has too many entries: $count (max $MaxManifestEntries)")),
        )
        .consume(count => listOfN(provide(count), manifestEntryCodec))(entries => entries.length),
      nonNegativeLong("total size"),
    )

  private val manifestCodec: Codec[Manifest] =
    combine(versionCodec, entriesWithSizeCodec)
      .exmap(
        { case (_, (entries, size)) => Attempt.fromEither(Manifest.validate(Manifest(entries, size)).left.map(Err(_))) },
        manifest => Attempt.fromEither(Manifest.validate(manifest).left.map(Err(_))).map(valid => ((), (valid.entries, valid.size))),
      )
      .complete
      .withContext("manifest frame")

  def encode(manifest: Manifest): Either[String, Frame] =
    manifestCodec.encode(manifest).toEither.left.map(_.message).map(bits => Frame(bits.toByteArray))

  def decode(frame: Frame): Either[String, Manifest] =
    manifestCodec.decode(BitVector(frame.bytes)) match
      case Attempt.Successful(DecodeResult(value, _)) => Right(value)
      case Attempt.Failure(err)                       => Left(err.message)
