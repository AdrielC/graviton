package graviton.core.manifest

import graviton.core.bytes.*
import graviton.core.keys.*
import graviton.core.ranges.Span
import zio.*
import zio.test.*
import zio.test.Assertion.*

object FramedManifestSpec extends ZIOSpecDefault:

  private val zeroDigest = "0" * HashAlgo.Sha256.hexLength

  private def makeBits(size: Long): ZIO[Any, String, KeyBits] =
    for
      digest <- ZIO.fromEither(Digest.make(HashAlgo.Sha256)(zeroDigest)).mapError(_.toString)
      bits   <- ZIO.fromEither(KeyBits.create(HashAlgo.Sha256, digest, size)).mapError(_.toString)
    yield bits

  override def spec: Spec[TestEnvironment, Any] =
    suite("FramedManifest")(
      test("round-trips manifests with metadata and view transforms") {
        val attrs = Map("name" -> "segment-a", "encoding" -> "utf-8")
        val view  = ViewTransform("resize", Map("width" -> "100", "height" -> "200"), Some("public"))

        val program =
          for
            bits1    <- makeBits(12L)
            bits2    <- makeBits(5L)
            manifest <- ZIO
                          .fromEither(
                            Manifest.fromEntries(
                              List(
                                ManifestEntry(BinaryKey.Blob(bits1), Span.unsafe(0L, 11L), attrs),
                                ManifestEntry(BinaryKey.View(bits2, view), Span.unsafe(12L, 16L), Map.empty),
                              )
                            )
                          )
                          .mapError(_.toString)
            encoded  <- ZIO.fromEither(FramedManifest.encode(manifest)).mapError(_.toString)
            decoded  <- ZIO.fromEither(FramedManifest.decode(encoded)).mapError(_.toString)
          yield assertTrue(decoded == manifest)

        program.mapError(TestFailure.fail)
      },
      test("rejects overlapping spans during encoding") {
        val attrs = Map("name" -> "overlap")

        val program =
          for
            bits    <- makeBits(8L)
            entry1   = ManifestEntry(BinaryKey.Blob(bits), Span.unsafe(0L, 9L), attrs)
            entry2   = ManifestEntry(BinaryKey.Blob(bits), Span.unsafe(8L, 12L), Map.empty)
            manifest = Manifest(List(entry1, entry2), size = 13L)
            result  <- ZIO.fromEither(FramedManifest.encode(manifest)).either
          yield assert(result)(isLeft(containsString("non-overlapping")))

        program.mapError(TestFailure.fail)
      },
      test("fails decoding on version mismatch") {
        val program =
          for
            bits     <- makeBits(4L)
            manifest <- ZIO
                          .fromEither(
                            Manifest.fromEntries(List(ManifestEntry(BinaryKey.Block(bits), Span.unsafe(0L, 3L), Map("p" -> "q"))))
                          )
                          .mapError(_.toString)
            encoded  <- ZIO.fromEither(FramedManifest.encode(manifest)).mapError(_.toString)
            corrupted =
              encoded.copy(bytes = encoded.bytes.updated(0, 99.toByte))
            decoded  <- ZIO.succeed(FramedManifest.decode(corrupted))
          yield assert(decoded)(isLeft(containsString("Unsupported manifest frame version")))

        program.mapError(TestFailure.fail)
      },
    )
