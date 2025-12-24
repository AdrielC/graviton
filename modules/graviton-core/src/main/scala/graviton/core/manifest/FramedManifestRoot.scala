package graviton.core.manifest

import graviton.core.codec.BinaryKeyCodec
import graviton.core.keys.BinaryKey
import graviton.core.ranges.Span

import scodec.*
import scodec.bits.BitVector
import scodec.codecs.*

object FramedManifestRoot:
  final case class Frame(bytes: Array[Byte])

  private val Version: Byte = 1

  // Hard bounds to avoid OOM-by-valid-input.
  private val MaxPages: Int = 65535

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
        case other             => Attempt.failure(Err(s"Unsupported manifest-root frame version $other (expected $Version)"))
      },
      _ => Attempt.successful(Version),
    )

  private def nonNegativeInt(context: String): Codec[Int] =
    int32.exmap(
      value =>
        if value >= 0 then Attempt.successful(value)
        else Attempt.failure(Err(s"$context cannot be negative: $value")),
      value => Attempt.successful(value),
    )

  private def nonNegativeLong(context: String): Codec[Long] =
    int64.exmap(
      value =>
        if value >= 0 then Attempt.successful(value)
        else Attempt.failure(Err(s"$context cannot be negative: $value")),
      value => Attempt.successful(value),
    )

  private val pageRefCodec: Codec[ManifestPageRef] =
    combine(combine(combine(BinaryKeyCodec.codec, int64), int64), uint16).exmap(
      { case (((key, start), end), entryCount) =>
        key match
          case manifestKey: BinaryKey.Manifest =>
            Attempt.fromEither(
              Span
                .make(start, end)
                .left
                .map(Err(_))
                .flatMap(span => Right(ManifestPageRef(manifestKey, span, entryCount.toInt)))
            )
          case other                           =>
            Attempt.failure(Err(s"Manifest root page refs must contain manifest keys, got $other"))
      },
      ref => Attempt.successful((((ref.key: BinaryKey, ref.span.startInclusive), ref.span.endInclusive), ref.entryCount)),
    )

  private val pagesCodec: Codec[List[ManifestPageRef]] =
    nonNegativeInt("page count")
      .exmap(
        count =>
          if count <= MaxPages then Attempt.successful(count)
          else Attempt.failure(Err(s"Manifest root has too many pages: $count (max $MaxPages)")),
        count =>
          if count <= MaxPages then Attempt.successful(count)
          else Attempt.failure(Err(s"Manifest root has too many pages: $count (max $MaxPages)")),
      )
      .consume(count => listOfN(provide(count), pageRefCodec))(refs => refs.length)

  private val rootCodec: Codec[ManifestRoot] =
    combine(versionCodec, combine(pagesCodec, nonNegativeLong("total size")))
      .exmap(
        { case (_, (pages, size)) =>
          Attempt.fromEither(ManifestRoot.validate(ManifestRoot(pages, size)).left.map(Err(_)))
        },
        root => Attempt.fromEither(ManifestRoot.validate(root).left.map(Err(_))).map(valid => ((), (valid.pages, valid.size))),
      )
      .complete
      .withContext("manifest root frame")

  def encode(root: ManifestRoot): Either[String, Frame] =
    rootCodec.encode(root).toEither.left.map(_.message).map(bits => Frame(bits.toByteArray))

  def decode(frame: Frame): Either[String, ManifestRoot] =
    rootCodec.decode(BitVector(frame.bytes)) match
      case Attempt.Successful(DecodeResult(value, _)) => Right(value)
      case Attempt.Failure(err)                       => Left(err.message)
