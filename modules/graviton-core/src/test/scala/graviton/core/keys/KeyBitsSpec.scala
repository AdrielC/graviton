package graviton.core.keys

import graviton.core.bytes.{Digest, HashAlgo}
import zio.test.*

object KeyBitsSpec extends ZIOSpecDefault:

  override def spec =
    suite("KeyBits")(
      test("render and fromString round-trip a content key") {
        val parsed = for
          digest <- Digest.fromString("a" * 64)
          bits   <- KeyBits.create(HashAlgo.Sha256, digest, 42L)
          copy   <- KeyBits.fromString(bits.render)
        yield (bits, copy)

        assertTrue(parsed.exists { case (bits, copy) => bits == copy })
      },
      test("fromString rejects arbitrary text instead of hashing it") {
        assertTrue(
          KeyBits.fromString("hello").left.exists(_.contains("<algorithm>:<hex-digest>:<byte-length>"))
        )
      },
      test("fromString validates digest length for the selected algorithm") {
        assertTrue(
          KeyBits.fromString(s"sha-256:${"a" * 40}:4").left.exists(_.contains("Digest length mismatch"))
        )
      },
    )
