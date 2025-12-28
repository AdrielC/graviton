package graviton.core.manifest

import graviton.core.bytes.{Digest, HashAlgo}
import graviton.core.keys.{BinaryKey, KeyBits}
import graviton.core.ranges.Span
import graviton.core.types.{ManifestAnnotationKey, ManifestAnnotationValue}
import zio.test.*

object PagedManifestSpec extends ZIOSpecDefault:

  private val zeroDigest = "0" * HashAlgo.Sha256.hexLength

  private def blobKey(size: Long): BinaryKey.Blob =
    val bits =
      (for
        digest <- Digest.fromString(zeroDigest)
        bits   <- KeyBits.create(HashAlgo.Sha256, digest, size)
        key    <- BinaryKey.blob(bits)
      yield key).toOption.get
    bits

  override def spec: Spec[TestEnvironment, Any] =
    suite("PagedManifest")(
      test("paginates large manifests into <= MaxManifestEntries pages and materializes keys") {
        val entryCount = FramedManifest.MaxManifestEntries * 2 + 5

        val entries =
          (0 until entryCount).toList.map { i =>
            ManifestEntry(
              key = blobKey(1L),
              span = Span.unsafe(i.toLong, i.toLong),
              annotations = Map.empty[ManifestAnnotationKey, ManifestAnnotationValue],
            )
          }

        val pagedEither = PagedManifest.paginate(entries)

        assertTrue(pagedEither.isRight) &&
        assertTrue {
          val paged = pagedEither.toOption.get
          paged.pages.forall(_.entries.length <= FramedManifest.MaxManifestEntries)
        } &&
        assertTrue {
          val paged = pagedEither.toOption.get
          val mat   = PagedManifest.materializeKeys(paged, HashAlgo.Sha256)
          mat.isRight
        }
      },
      test("root frame round-trips after materializing keys") {
        val entryCount = FramedManifest.MaxManifestEntries + 3

        val entries =
          (0 until entryCount).toList.map { i =>
            ManifestEntry(
              key = blobKey(1L),
              span = Span.unsafe(i.toLong, i.toLong),
              annotations = Map.empty[ManifestAnnotationKey, ManifestAnnotationValue],
            )
          }

        val program =
          for
            paged         <- PagedManifest.paginate(entries)
            (root, keyed) <- PagedManifest.materializeKeys(paged, HashAlgo.Sha256)
            encoded       <- FramedManifestRoot.encode(root)
            decoded       <- FramedManifestRoot.decode(encoded)
          yield (root, decoded, keyed)

        assertTrue(program.isRight) &&
        assertTrue {
          val (root, decoded, keyed) = program.toOption.get
          decoded == root && keyed.nonEmpty
        }
      },
    )
