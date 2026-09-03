package graviton.core.keys

import graviton.core.bytes.{Digest, HashAlgo}
import graviton.core.types.{BlockSize, ContentLength, FileSize, MaxBlockBytes}
import zio.test.*

object KeyBitsSpec extends ZIOSpecDefault:

  override def spec =
    suite("KeyBits")(
      test("render and fromString round-trip a content key") {
        val parsed = for
          digest                <- Digest.fromString("a" * 64)
          bits                  <- KeyBits.fromLong(HashAlgo.Sha256, digest, 42L)
          rendered: ContentKeyId = bits.render
          copy                  <- KeyBits.parse(rendered)
        yield (bits, rendered, copy)

        assertTrue(parsed.exists { case (bits, rendered, copy) =>
          bits == copy &&
          rendered == s"sha-256:${"a" * 64}:42" &&
          rendered.length <= ContentKeyId.MaxLength
        })
      },
      test("content key identifiers parse and canonicalize untrusted aliases") {
        val digest = "a" * 64

        assertTrue(
          ContentKeyId.fromString(s"SHA256:$digest:42").toEither.contains(s"sha-256:$digest:42"),
          ContentKeyId.fromString("not-a-content-key").isFailure,
        )
      },
      test("fromString rejects arbitrary text instead of hashing it") {
        assertTrue(
          KeyBits
            .fromString("hello")
            .toEither
            .left
            .exists(_.exists(_.message.contains("<algorithm>:<hex-digest>:<byte-length>")))
        )
      },
      test("fromString validates digest length for the selected algorithm") {
        assertTrue(
          KeyBits
            .fromString(s"sha-256:${"a" * 40}:4")
            .toEither
            .left
            .exists(_.contains(KeyBitsError.DigestLengthMismatch(HashAlgo.Sha256, 32, 20)))
        )
      },
      test("fromString accumulates independent field errors") {
        val errors = KeyBits.fromString("unknown:NOT-HEX:-1").toEither.swap.toOption

        assertTrue(
          errors.exists(
            _.toChunk == zio.Chunk(
              KeyBitsError.UnsupportedAlgorithm("unknown"),
              KeyBitsError.InvalidDigest("Digest must contain lowercase hexadecimal characters only"),
              KeyBitsError.InvalidSize("Invalid byte length '-1'"),
            )
          )
        )
      },
      test("fromString rejects non-ASCII numeric syntax") {
        val digest = "a" * 64

        assertTrue(
          KeyBits.fromString(s"sha-256:$digest:١").toEither.isLeft,
          KeyBits.fromString(s"sha-256:${"١" * 64}:1").toEither.isLeft,
        )
      },
      test("fromLong enforces the shared content-length bounds") {
        val digest = Digest.fromString("a" * 64).toOption.get

        assertTrue(
          KeyBits.fromLong(HashAlgo.Sha256, digest, -1L).isLeft,
          KeyBits.fromLong(HashAlgo.Sha256, digest, 0L).exists(_.size == ContentLength.Zero),
          KeyBits.fromLong(HashAlgo.Sha256, digest, FileSize.Max + 1L).isLeft,
        )
      },
      test("block construction enforces block-specific bounds") {
        val result = for
          digest      <- Digest.fromString("a" * 64)
          maximumBits <- KeyBits.fromLong(HashAlgo.Sha256, digest, MaxBlockBytes.toLong)
          oversized   <- KeyBits.fromLong(HashAlgo.Sha256, digest, MaxBlockBytes.toLong + 1L)
        yield (BinaryKey.block(maximumBits), BinaryKey.block(oversized))

        assertTrue(result.exists { case (maximum, oversized) => maximum.isRight && oversized.isLeft })
      },
      test("kind-specific sizes widen into content length without losing their value") {
        val result =
          for
            block       <- BlockSize.either(MaxBlockBytes)
            file        <- FileSize.either(42L)
            blockLength <- ContentLength.fromBlockSize(block)
          yield (
            blockLength,
            ContentLength.fromFileSize(file),
          )

        assertTrue(result.exists { case (block, file) =>
          block == MaxBlockBytes.toLong && file == 42L
        })
      },
      test("blob construction rejects a zero-length generic content key") {
        val result = for
          digest <- Digest.fromString("a" * 64)
          bits   <- KeyBits.fromLong(HashAlgo.Sha256, digest, 0L)
        yield BinaryKey.blob(bits)

        assertTrue(result.exists(_.isLeft))
      },
    )
