package graviton.server

import graviton.backend.pg.{PgBlobManifestRepo, PgDataSource}
import graviton.backend.s3.S3BlockStore
import graviton.core.types.UploadChunkSize
import graviton.runtime.model.BlobWritePlan
import graviton.runtime.stores.{BlobStore, CasBlobStore}
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
 * - GRAVITON_IT=1
 * - PG_JDBC_URL / PG_USERNAME / PG_PASSWORD
 * - QUASAR_MINIO_URL / MINIO_ROOT_USER / MINIO_ROOT_PASSWORD
 */
object MinioCasRoundTripSpec extends ZIOSpecDefault:

  private val enabled: Boolean =
    sys.env.get("GRAVITON_MINIO_IT").exists(v => v.trim == "1" || v.trim.equalsIgnoreCase("true"))

  private val blobLayer: ZLayer[Any, Throwable, BlobStore] =
    ZLayer.make[BlobStore](
      PgDataSource.layerFromEnv,
      PgBlobManifestRepo.layer,
      S3BlockStore.layerFromEnv,
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
          val data =
            Chunk.fromArray(("hello-minio-cas-" * 2000).getBytes(StandardCharsets.UTF_8))

          for
            chunkSize <- ZIO.fromEither(UploadChunkSize.either(1024)).mapError(msg => new IllegalArgumentException(msg))
            chunker    = Chunker.fixed(chunkSize)
            store     <- ZIO.service[BlobStore]
            written   <- Chunker.locally(chunker) {
                           ZStream.fromChunk(data).run(store.put(BlobWritePlan()))
                         }
            readBack  <- store.get(written.key).runCollect
          yield assertTrue(readBack == data)
        }
      ).provideShared(blobLayer) @@ TestAspect.sequential
