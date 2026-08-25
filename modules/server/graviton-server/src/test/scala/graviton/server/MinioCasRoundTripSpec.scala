package graviton.server

import graviton.backend.pg.{PgBlobManifestRepo, PgDataSource}
import graviton.backend.s3.S3BlockStore
import graviton.core.types.UploadChunkSize
import graviton.runtime.model.BlobWritePlan
import graviton.runtime.stores.{BlobStore, BlockStore, CasBlobStore}
import graviton.streams.Chunker
import zio.*
import zio.stream.ZStream
import zio.test.*

import java.nio.charset.StandardCharsets

/**
 * Opt-in integration test: requires a running Postgres (authoritative schema applied)
 * and MinIO (buckets created) matching the on-prem compose.
 *
 * Enable with:
 * - GRAVITON_MINIO_IT=1
 * - PG_JDBC_URL / PG_USERNAME / PG_PASSWORD
 * - QUASAR_MINIO_URL / MINIO_ROOT_USER / MINIO_ROOT_PASSWORD
 */
object MinioCasRoundTripSpec extends ZIOSpecDefault:

  private val enabled: Boolean =
    sys.env.get("GRAVITON_MINIO_IT").exists(v => v.trim == "1" || v.trim.equalsIgnoreCase("true"))

  private val s3StoreLayer: ZLayer[Any, Throwable, S3BlockStore & BlockStore] =
    ZLayer.fromZIO(S3BlockStore.fromEnvironment).flatMap { environment =>
      val store = environment.get[S3BlockStore]
      ZLayer.succeed[S3BlockStore](store) ++ ZLayer.succeed[BlockStore](store)
    }

  private val blobLayer: ZLayer[Any, Throwable, BlobStore & S3BlockStore] =
    ZLayer.make[BlobStore & S3BlockStore](
      PgDataSource.layerFromEnv,
      PgBlobManifestRepo.layer,
      s3StoreLayer,
      CasBlobStore.layer,
    )

  override def spec: Spec[TestEnvironment, Any] =
    if !enabled then
      suite("MinIO + Postgres CAS round-trip")(
        test("skipped (set GRAVITON_MINIO_IT=1 to enable)") {
          ZIO.succeed(assertTrue(true))
        }
      )
    else
      suite("MinIO + Postgres CAS round-trip")(
        test("upload then download matches bytes (Chunker.fixed)") {
          val data    =
            Chunk.fromArray(("hello-minio-cas-" * 2000).getBytes(StandardCharsets.UTF_8))
          val chunker = Chunker.fixed(UploadChunkSize(1024))

          for
            store    <- ZIO.service[BlobStore]
            written  <- Chunker.locally(chunker) {
                          ZStream.fromChunk(data).run(store.put(BlobWritePlan()))
                        }
            readBack <- store.get(written.key).runCollect
          yield assertTrue(readBack == data)
        },
        test("S3 block quarantine can be restored without losing blob bytes") {
          val data = Chunk.fromArray(("s3-quarantine-restore-" * 100).getBytes(StandardCharsets.UTF_8))

          for
            store       <- ZIO.service[BlobStore]
            s3          <- ZIO.service[S3BlockStore]
            written     <- ZStream.fromChunk(data).run(store.put(BlobWritePlan()))
            description <- store.inspect(written.key).someOrFail(new NoSuchElementException("manifest not found"))
            blockKey     = description.blocks.head.key
            entry       <- s3.inventory.filter(_.key == blockKey).runHead.someOrFail(new NoSuchElementException("block not inventoried"))
            quarantined <- s3.quarantine(entry)
            absent      <- s3.exists(blockKey)
            _           <- s3.restore(quarantined)
            present     <- s3.exists(blockKey)
            readBack    <- store.get(written.key).runCollect
          yield assertTrue(!absent, present, readBack == data)
        },
      ).provideShared(blobLayer) @@ TestAspect.sequential
