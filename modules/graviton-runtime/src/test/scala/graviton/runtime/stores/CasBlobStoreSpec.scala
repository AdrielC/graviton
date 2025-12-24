package graviton.runtime.stores

import graviton.core.attributes.BinaryAttributes
import graviton.core.keys.BinaryKey
import graviton.core.manifest.Manifest
import graviton.core.types.UploadChunkSize
import graviton.runtime.model.BlobWritePlan
import graviton.streams.Chunker
import zio.*
import zio.stream.ZStream
import zio.test.*

import java.nio.charset.StandardCharsets

object CasBlobStoreSpec extends ZIOSpecDefault:

  private final class InMemoryManifestRepo(ref: Ref[Map[BinaryKey.Blob, Manifest]]) extends BlobManifestRepo:
    override def put(blob: BinaryKey.Blob, manifest: Manifest): ZIO[Any, Throwable, Unit] =
      ref.update(_.updated(blob, manifest)).unit

    override def streamBlockRefs(blob: BinaryKey.Blob): ZStream[Any, Throwable, graviton.runtime.streaming.BlobStreamer.BlockRef] =
      ZStream.fromZIO(ref.get.map(_.get(blob))).flatMap {
        case None    =>
          ZStream.fail(new NoSuchElementException("Missing manifest"))
        case Some(m) =>
          ZStream.fromIterable(
            m.entries.zipWithIndex.collect { case (graviton.core.manifest.ManifestEntry(b: BinaryKey.Block, _, _), idx) =>
              graviton.runtime.streaming.BlobStreamer.BlockRef(idx.toLong, b)
            }
          )
      }

  override def spec: Spec[TestEnvironment, Any] =
    suite("CasBlobStore")(
      test("uses Chunker boundaries for block spans") {
        val bytes = "x" * 2500
        val data  = Chunk.fromArray(bytes.getBytes(StandardCharsets.UTF_8))

        for
          chunkSize <- ZIO.fromEither(UploadChunkSize.either(1024)).mapError(msg => new IllegalArgumentException(msg))
          chunker    = Chunker.fixed(chunkSize)

          blockStore <- InMemoryBlockStore.make
          manifestsR <- Ref.make(Map.empty[BinaryKey.Blob, Manifest])
          repo        = InMemoryManifestRepo(manifestsR)
          blobStore   = new CasBlobStore(blockStore, repo)

          result <- Chunker.locally(chunker) {
                      ZStream.fromChunk(data).run(blobStore.put(BlobWritePlan(attributes = BinaryAttributes.empty)))
                    }

          blobKey  <- ZIO
                        .fromEither(
                          result.key match
                            case b: BinaryKey.Blob => Right(b)
                            case other             => Left(s"Expected blob key, got $other")
                        )
                        .mapError(msg => new IllegalStateException(msg))
          manifest <- manifestsR.get
                        .map(_.get(blobKey))
                        .someOrFail(new NoSuchElementException("Manifest missing"))

          spans = manifest.entries.map(_.span)
        yield assertTrue(
          manifest.entries.length == 3,
          spans.head.startInclusive == 0L,
          spans.head.endInclusive == 1023L,
          spans(1).startInclusive == 1024L,
          spans(1).endInclusive == 2047L,
          spans(2).startInclusive == 2048L,
          spans(2).endInclusive == 2499L,
        )
      }
    )
