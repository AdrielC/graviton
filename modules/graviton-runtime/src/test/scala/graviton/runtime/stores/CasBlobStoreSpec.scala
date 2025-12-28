package graviton.runtime.stores

import graviton.core.attributes.BinaryAttributes
import graviton.core.keys.BinaryKey
import graviton.core.manifest.Manifest
import graviton.core.types.UploadChunkSize
import graviton.runtime.metrics.{InMemoryMetricsRegistry, MetricKey, MetricKeys}
import graviton.runtime.model.{BlobWritePlan, IngestProgram}
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
          spans.head.startInclusive.value == 0L,
          spans.head.endInclusive.value == 1023L,
          spans(1).startInclusive.value == 1024L,
          spans(1).endInclusive.value == 2047L,
          spans(2).startInclusive.value == 2048L,
          spans(2).endInclusive.value == 2499L,
        )
      },
      test("applies BlobWritePlan.program pipeline before chunking + hashing") {
        val input = "a-b-c-d"
        val data  = Chunk.fromArray(input.getBytes(StandardCharsets.UTF_8))

        val program =
          IngestProgram.UsePipeline(
            zio.stream.ZPipeline.filter[Byte](_ != '-'.toByte)
          )

        for
          chunkSize <- ZIO.fromEither(UploadChunkSize.either(2)).mapError(msg => new IllegalArgumentException(msg))
          chunker    = Chunker.fixed(chunkSize)

          blockStore <- InMemoryBlockStore.make
          manifestsR <- Ref.make(Map.empty[BinaryKey.Blob, Manifest])
          repo        = InMemoryManifestRepo(manifestsR)
          blobStore   = new CasBlobStore(blockStore, repo)

          result <- Chunker.locally(chunker) {
                      ZStream
                        .fromChunk(data)
                        .run(
                          blobStore.put(
                            BlobWritePlan(
                              attributes = BinaryAttributes.empty,
                              program = program,
                            )
                          )
                        )
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

          bytes <- blobStore.get(blobKey).runCollect
        yield assertTrue(
          bytes == Chunk.fromArray("abcd".getBytes(StandardCharsets.UTF_8)),
          manifest.entries.length == 2,
          manifest.entries.map(_.span.startInclusive) == List(0L, 2L),
          manifest.entries.map(_.span.endInclusive) == List(1L, 3L),
        )
      },
      test("supports IngestProgram.UseScan without breaking ingest (records metrics)") {
        val input = "hello"
        val data  = Chunk.fromArray(input.getBytes(StandardCharsets.UTF_8))

        val program =
          IngestProgram.UseScan(
            label = "byte-count",
            build = () => graviton.core.scan.FS.counter[Byte],
          )

        for
          registry <- InMemoryMetricsRegistry.make

          chunkSize <- ZIO.fromEither(UploadChunkSize.either(2)).mapError(msg => new IllegalArgumentException(msg))
          chunker    = Chunker.fixed(chunkSize)

          blockStore <- InMemoryBlockStore.make
          manifestsR <- Ref.make(Map.empty[BinaryKey.Blob, Manifest])
          repo        = InMemoryManifestRepo(manifestsR)
          blobStore   = new CasBlobStore(blockStore, repo, metrics = registry)

          result <- Chunker.locally(chunker) {
                      ZStream
                        .fromChunk(data)
                        .run(blobStore.put(BlobWritePlan(program = program)))
                    }

          blobKey <- ZIO
                       .fromEither(
                         result.key match
                           case b: BinaryKey.Blob => Right(b)
                           case other             => Left(s"Expected blob key, got $other")
                       )
                       .mapError(msg => new IllegalStateException(msg))

          bytes <- blobStore.get(blobKey).runCollect

          snapshot <- registry.snapshot

          tags =
            Map(
              "backend" -> "cas",
              "store"   -> "blob",
              "chunker" -> chunker.name,
              "program" -> "scan",
              "scan"    -> "byte-count",
            )
        yield assertTrue(
          bytes == data,
          snapshot.gauges.contains(MetricKey(MetricKeys.BytesIngested, tags)),
          snapshot.gauges.contains(MetricKey(MetricKeys.BlocksIngested, tags)),
          snapshot.gauges.contains(MetricKey(MetricKeys.ScanOutputs, tags)),
          snapshot.gauges.contains(MetricKey(MetricKeys.UploadDuration, tags)),
        )
      },
    )
