package graviton.core.manifest

import graviton.core.codec.BinaryKeyCodec
import graviton.core.ranges.Span

import scodec.*
import scodec.bits.BitVector
import scodec.codecs.*

object FramedManifest:
  final case class Frame(bytes: Array[Byte])

  private val Version: Byte = 1

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

  private val attributesCodec: Codec[Map[String, String]] =
    listOfN(uint16, combine(variableSizeBytes(uint16, utf8), variableSizeBytes(uint16, utf8))).exmap(
      pairs =>
        val builder                   = Map.newBuilder[String, String]
        val seen                      = scala.collection.mutable.HashSet.empty[String]
        var duplicate: Option[String] = None

        pairs.foreach { case (key, value) =>
          if !seen.add(key) then duplicate = Some(key)
          builder += key -> value
        }

        duplicate match
          case Some(key) => Attempt.failure(Err(s"Duplicate key '$key' in attributes"))
          case None      => Attempt.successful(builder.result())
      ,
      map => Attempt.successful(map.toList.sortBy(_._1)),
    )

  private val manifestEntryCodec: Codec[ManifestEntry] =
    combine(combine(combine(BinaryKeyCodec.codec, int64), int64), attributesCodec).exmap(
      { case (((key, start), end), attrs) =>
        Attempt.fromEither(Span.make(start, end).map(span => ManifestEntry(key, span, attrs)).left.map(Err(_)))
      },
      entry => Attempt.successful((((entry.key, entry.span.startInclusive), entry.span.endInclusive), entry.attributes)),
    )

  private val entriesWithSizeCodec: Codec[(List[ManifestEntry], Long)] =
    combine(
      nonNegativeInt("entry count")
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
