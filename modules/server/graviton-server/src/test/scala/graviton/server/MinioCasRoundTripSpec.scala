package graviton.server

import graviton.backend.pg.{PgBlobManifestRepo, PgDataSource}
import graviton.backend.s3.{S3BlockStore, S3ClientLayer, S3Config, S3MutableObjectStore, S3ObjectStoreConfig}
import graviton.core.locator.BlobLocator
import graviton.core.types.{LocatorBucket, LocatorPath, LocatorScheme, UploadChunkSize}
import graviton.runtime.model.BlobWritePlan
import graviton.runtime.stores.{BlobStore, BlockStore, CasBlobStore}
import graviton.streams.Chunker
import zio.*
import zio.stream.ZStream
import zio.test.*
import software.amazon.awssdk.services.s3.S3Client

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

  private val objectClientLayer: ZLayer[Any, Throwable, S3Client] =
    ZLayer.scoped {
      for
        bucket <- ZIO.succeed(sys.env.get("GRAVITON_S3_BLOCK_BUCKET").filter(_.nonEmpty).getOrElse("graviton-blocks"))
        config <- ZIO.fromEither(S3Config.fromEnvironment(bucket)).mapError(new IllegalArgumentException(_))
        client <- ZIO.acquireRelease(S3ClientLayer.make(config))(value => ZIO.attemptBlocking(value.close()).orDie)
      yield client
    }

  private val integrationLayer: ZLayer[Any, Throwable, BlobStore & S3BlockStore & S3Client] =
    blobLayer ++ objectClientLayer

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
        test("S3 object backend performs real multipart put/get/list/copy/delete") {
          val bucket = sys.env.get("GRAVITON_S3_BLOCK_BUCKET").filter(_.nonEmpty).getOrElse("graviton-blocks")
          val source = BlobLocator(
            LocatorScheme.applyUnsafe("s3"),
            LocatorBucket.applyUnsafe(bucket),
            LocatorPath.applyUnsafe("objects/source"),
          )
          val copy   = source.copy(path = LocatorPath.applyUnsafe("objects/copy"))
          val data   = Chunk.fromIterable(0 until (12 * 1024 * 1024 + 37)).map(index => (index % 251).toByte)

          for
            client  <- ZIO.service[S3Client]
            config  <- ZIO
                         .fromEither(S3Config.fromEnvironment(bucket, prefix = "graviton-object-it"))
                         .mapError(new IllegalArgumentException(_))
            store    = new S3MutableObjectStore(client, S3ObjectStoreConfig(config))
            _       <- ZStream.fromChunk(data).rechunk(73 * 1024 + 11).run(store.put(source))
            size    <- store.head(source)
            loaded  <- store.get(source).runCollect
            _       <- store.copy(source, copy)
            copied  <- store.get(copy).runCollect
            listed  <- store.list("objects/").runCollect
            _       <- store.delete(source)
            deleted <- store.head(source)
            _       <- store.delete(copy)
          yield assertTrue(
            size.contains(data.length.toLong),
            loaded == data,
            copied == data,
            listed.toSet == Set(source, copy),
            deleted.isEmpty,
          )
        },
        test("S3 multipart upload aborts when its source stream fails") {
          val bucket  = sys.env.get("GRAVITON_S3_BLOCK_BUCKET").filter(_.nonEmpty).getOrElse("graviton-blocks")
          val locator = BlobLocator(
            LocatorScheme.applyUnsafe("s3"),
            LocatorBucket.applyUnsafe(bucket),
            LocatorPath.applyUnsafe("objects/interrupted"),
          )

          for
            client <- ZIO.service[S3Client]
            config <- ZIO
                        .fromEither(S3Config.fromEnvironment(bucket, prefix = "graviton-object-it"))
                        .mapError(new IllegalArgumentException(_))
            store   = new S3MutableObjectStore(client, S3ObjectStoreConfig(config))
            exit   <- (ZStream.fromChunk(Chunk.fill(6 * 1024 * 1024)(1.toByte)) ++ ZStream.fail(new RuntimeException("boom")))
                        .run(store.put(locator))
                        .exit
            head   <- store.head(locator)
          yield assertTrue(exit.isFailure, head.isEmpty)
        },
      ).provideShared(integrationLayer) @@ TestAspect.sequential
