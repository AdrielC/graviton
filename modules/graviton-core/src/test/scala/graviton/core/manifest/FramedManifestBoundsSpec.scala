package graviton.core.manifest

import graviton.core.bytes.{Digest, HashAlgo}
import graviton.core.keys.{BinaryKey, KeyBits}
import graviton.core.ranges.Span
import zio.*
import zio.test.*
import zio.test.Assertion.*

object FramedManifestBoundsSpec extends ZIOSpecDefault:

  private val zeroDigest = "0" * HashAlgo.Sha256.hexLength

  private def makeBits(size: Long): Either[String, KeyBits] =
    for
      digest <- Digest.fromString(zeroDigest)
      bits   <- KeyBits.create(HashAlgo.Sha256, digest, size)
    yield bits

  override def spec: Spec[TestEnvironment, Any] =
    suite("FramedManifest bounds")(
      test("encode rejects too many manifest entries") {
        val bits     = makeBits(1L).toOption.get
        val blobKey  = BinaryKey.blob(bits).toOption.get
        val entries  =
          (0 until 20000).toList.map { i =>
            ManifestEntry(blobKey, Span.unsafe(i.toLong, i.toLong), Map.empty)
          }
        val manifest = Manifest(entries, size = entries.length.toLong)
        val res      = FramedManifest.encode(manifest)
        assert(res)(isLeft(containsString("too many entries")))
      }
    )
