package graviton.core.manifest

import graviton.core.bytes.{Digest, HashAlgo}
import graviton.core.keys.*
import graviton.core.ranges.Span
import graviton.core.types.{ManifestAnnotationKey, ManifestAnnotationValue}
import zio.*
import zio.test.*
import zio.test.Assertion.*

object FramedManifestSpec extends ZIOSpecDefault:

  private val zeroDigest = "0" * HashAlgo.Sha256.hexLength

  private def makeBits(size: Long): ZIO[Any, String, KeyBits] =
    ZIO.fromEither:
      for
        digest <- Digest.make(HashAlgo.Sha256)(zeroDigest)
        bits   <- KeyBits.create(HashAlgo.Sha256, digest, size)
      yield bits

  override def spec: Spec[TestEnvironment, Any] =
    suite("FramedManifest")(
      test("round-trips manifests with metadata and view transforms") {
        val attrs =
          Map(
            ManifestAnnotationKey.applyUnsafe("name")     -> ManifestAnnotationValue.applyUnsafe("segment-a"),
            ManifestAnnotationKey.applyUnsafe("encoding") -> ManifestAnnotationValue.applyUnsafe("utf-8"),
          )
        val view  =
          ViewTransform
            .from("resize", Map("width" -> "100", "height" -> "200"), Some("public"))
            .toOption
            .get

        val program =
          for
            bits1    <- makeBits(12L)
            bits2    <- makeBits(5L)
            blobKey  <- ZIO.fromEither(BinaryKey.blob(bits1)).mapError(_.toString)
            viewKey  <- ZIO.fromEither(BinaryKey.View(bits2, view)).mapError(_.toString)
            manifest <- ZIO
                          .fromEither(
                            Manifest.fromEntries(
                              List(
                                ManifestEntry(blobKey, Span.unsafe(0L, 11L), attrs),
                                ManifestEntry(viewKey, Span.unsafe(12L, 16L), Map.empty),
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
        val attrs =
          Map(
            ManifestAnnotationKey.applyUnsafe("name") -> ManifestAnnotationValue.applyUnsafe("overlap")
          )

        val program =
          for
            bits    <- makeBits(8L)
            blobKey <- ZIO.fromEither(BinaryKey.blob(bits)).mapError(_.toString)
            entry1   = ManifestEntry(blobKey, Span.unsafe(0L, 9L), attrs)
            entry2   = ManifestEntry(blobKey, Span.unsafe(8L, 12L), Map.empty)
            manifest = Manifest(List(entry1, entry2), size = 13L)
            result  <- ZIO.fromEither(FramedManifest.encode(manifest)).either
          yield assert(result)(isLeft(containsString("non-overlapping")))

        program.mapError(TestFailure.fail)
      },
      test("fails decoding on version mismatch") {
        val program =
          for
            bits     <- makeBits(4L)
            blockKey <- ZIO.fromEither(BinaryKey.block(bits)).mapError(_.toString)
            manifest <- ZIO
                          .fromEither(
                            Manifest.fromEntries(
                              List(
                                ManifestEntry(
                                  blockKey,
                                  Span.unsafe(0L, 3L),
                                  Map(
                                    ManifestAnnotationKey.applyUnsafe("p") -> ManifestAnnotationValue.applyUnsafe("q")
                                  ),
                                )
                              )
                            )
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
