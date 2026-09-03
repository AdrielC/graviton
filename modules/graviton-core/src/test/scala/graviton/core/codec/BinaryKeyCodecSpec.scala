package graviton.core.codec

import graviton.core.bytes.{Digest, HashAlgo}
import graviton.core.keys.{BinaryKey, KeyBits, ViewTransform}
import scodec.bits.BitVector
import zio.test.*

object BinaryKeyCodecSpec extends ZIOSpecDefault:

  override def spec =
    suite("BinaryKeyCodec")(
      test("round-trips a canonical manifest-based view") {
        val result = for
          baseBits  <- keyBits("a", 42L)
          base      <- BinaryKey.manifest(baseBits)
          transform <- ViewTransform.from("preview", Map("page" -> "1"), Some("public"))
          view      <- BinaryKey.view(base, transform)
          encoded   <- BinaryKeyCodec.codec.encode(view).toEither.left.map(_.message)
          decoded   <- BinaryKeyCodec.codec.decode(encoded).toEither.left.map(_.message)
        yield (view, decoded.value, decoded.remainder)

        assertTrue(result.exists { case (view, decoded, remainder) =>
          decoded == view && remainder == BitVector.empty
        })
      },
      test("rejects non-manifest view bases through both construction paths") {
        val result = for
          baseBits  <- keyBits("b", 42L)
          blob      <- BinaryKey.blob(baseBits)
          manifest  <- BinaryKey.manifest(baseBits)
          transform <- ViewTransform.from("preview", Map("page" -> "1"), None)
          canonical <- BinaryKey.view(manifest, transform)
        yield (
          BinaryKey.view(blob, transform),
          BinaryKeyCodec.codec
            .encode(BinaryKey.View(canonical.bits, blob, transform))
            .toEither
            .left
            .map(_.message),
        )

        assertTrue(result.exists { case (constructed, encoded) =>
          constructed.left.exists(_.contains("must be a manifest key")) &&
          encoded.left.exists(_.contains("must be a manifest key"))
        })
      },
      test("rejects view bits that do not match canonical derivation") {
        val result = for
          baseBits       <- keyBits("c", 42L)
          mismatchedBits <- keyBits("d", 0L)
          base           <- BinaryKey.manifest(baseBits)
          transform      <- ViewTransform.from("preview", Map("page" -> "1"), None)
          canonical      <- BinaryKey.view(base, transform)
          encoded         = BinaryKeyCodec.codec
                              .encode(canonical.copy(bits = mismatchedBits))
                              .toEither
                              .left
                              .map(_.message)
        yield encoded

        assertTrue(result.exists(_.left.exists(_.contains("do not match the canonical derived view key"))))
      },
    )

  private def keyBits(hexDigit: String, size: Long): Either[String, KeyBits] =
    for
      digest <- Digest.fromString(hexDigit * HashAlgo.Sha256.hexLength)
      bits   <- KeyBits.fromLong(HashAlgo.Sha256, digest, size)
    yield bits

end BinaryKeyCodecSpec
