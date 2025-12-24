package graviton.core.canonical

import graviton.core.bytes.{Digest, HashAlgo}
import graviton.core.keys.{KeyBits, ViewTransform}
import zio.*
import zio.test.*

object CanonicalEncodingSpec extends ZIOSpecDefault:

  private def makeBits(size: Long, hex: String): Either[String, KeyBits] =
    for
      digest <- Digest.fromString(hex)
      bits   <- KeyBits.create(HashAlgo.Sha256, digest, size)
    yield bits

  override def spec: Spec[TestEnvironment, Any] =
    suite("CanonicalEncoding")(
      test("KeyBitsV1.encode is deterministic") {
        val zeroDigest = "0" * HashAlgo.Sha256.hexLength
        val bits       = makeBits(123L, zeroDigest).toOption.get
        val a          = CanonicalEncoding.KeyBitsV1.encode(bits)
        val b          = CanonicalEncoding.KeyBitsV1.encode(bits)
        assertTrue(a.sameElements(b))
      },
      test("ViewTransformV1.encode is stable under arg map iteration order") {
        val t1 = ViewTransform("x", Map("b" -> "2", "a" -> "1"), scope = Some("s"))
        val t2 = ViewTransform("x", Map("a" -> "1", "b" -> "2"), scope = Some("s"))
        val a  = CanonicalEncoding.ViewTransformV1.encode(t1)
        val b  = CanonicalEncoding.ViewTransformV1.encode(t2)
        assertTrue(a.sameElements(b))
      },
    )
