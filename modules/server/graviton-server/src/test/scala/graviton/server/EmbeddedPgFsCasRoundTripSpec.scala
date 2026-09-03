package graviton.server

import graviton.backend.pg.{
  PgBlobManifestRepo,
  PgCatalog,
  PgDataSource,
  PgKeyValueStore,
  PgMaintenanceCoordinator,
  PgMutableObjectStore,
  PgReplicaIndex,
  PgResumableUploadRepository,
  PgTenantDomainSnapshot,
  PgTenantBlobManifestRepo,
  PgTenantPolicyCatalog,
}
import graviton.core.keys.BinaryKey
import graviton.core.locator.BlobLocator
import graviton.core.manifest.ManifestEntry
import graviton.core.ranges.Span
import graviton.core.types.{BlobOffset, FileSize, LocatorBucket, LocatorPath, LocatorScheme, RepositoryNamespace}
import graviton.runtime.config.{MaintenanceConfig, TenantStorageConfig}
import graviton.runtime.catalog.{CatalogError, CatalogName}
import graviton.runtime.model.BlobWritePlan
import graviton.runtime.kv.{KvKey, KvValue}
import graviton.runtime.stores.{
  BlobManifestRepo,
  BlobStore,
  CasBlobStore,
  FsBlockStore,
  ManifestIntegrity,
  ManifestKeyId,
  ManifestKeyService,
  StoreError,
}
import graviton.runtime.tenant.{DeduplicationScope, TenantCellId, TenantLifecycle, TenantPolicyCatalog, TenantRoutingError}
import graviton.runtime.upload.*
import graviton.security.*
import graviton.core.types.UploadChunkSize
import graviton.streams.Chunker
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import zio.*
import zio.stream.ZStream
import zio.test.*
import zio.blocks.mediatype.MediaTypes

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.sql.Connection
import java.time.Instant
import java.util.UUID
import scala.collection.mutable.ArrayBuffer
import java.io.FileNotFoundException

/**
 * Integration test (no Docker):
 * - Embedded Postgres (Zonky)
 * - Filesystem BlockStore (local temp dir)
 * - Chunker-driven ingest pipeline
 */
object EmbeddedPgFsCasRoundTripSpec extends ZIOSpecDefault:

  private val enabled: Boolean =
    sys.env.get("GRAVITON_IT").exists(v => v.trim == "1" || v.trim.equalsIgnoreCase("true"))

  private val embeddedPgLayer: ZLayer[Any, Throwable, javax.sql.DataSource] =
    ZLayer.scoped {
      ZIO.acquireRelease(ZIO.attemptBlocking(EmbeddedPostgres.builder().setPort(0).start()))(pg =>
        ZIO.attemptBlocking(pg.close()).ignore
      ) flatMap { pg =>
        for
          ddl <- resolveDdlPath
          ds  <- ZIO.acquireRelease(
                   PgDataSource.makeTyped(
                     pg.getJdbcUrl("postgres", "postgres"),
                     "postgres",
                     "",
                     PgDataSource.PoolConfig.Default.copy(maximumPoolSize = 16, minimumIdle = 4),
                   )
                 )(PgDataSource.closeScoped)
          _   <- ZIO.acquireReleaseWith(ZIO.attemptBlocking(ds.getConnection))(c => ZIO.attemptBlocking(c.close()).ignore) { conn =>
                   ZIO.attemptBlocking(executeSqlFile(conn, ddl))
                 }
        yield ds
      }
    }

  private val blobStoreLayer: ZLayer[Any, Throwable, BlobStore & javax.sql.DataSource] =
    embeddedPgLayer >+> ZLayer.scoped {
      for
        ds   <- ZIO.service[javax.sql.DataSource]
        root <- ZIO.attemptBlocking(Files.createTempDirectory("graviton-fs-blocks"))
        _    <- ZIO.addFinalizer(ZIO.attemptBlocking(deleteRecursive(root)).orDie)
        repo  = new PgBlobManifestRepo(ds)
        bs    = new FsBlockStore(root)
      yield new CasBlobStore(bs, repo): BlobStore
    }

  override def spec: Spec[TestEnvironment, Any] =
    if !enabled then
      suite("Embedded PG + FS CAS round-trip")(
        test("skipped (set GRAVITON_IT=1 to enable)") {
          ZIO.succeed(assertTrue(true))
        }
      )
    else
      suite("Embedded PG + FS CAS round-trip")(
        test("upload then download matches bytes (Chunker.fixed)") {
          val data    =
            Chunk.fromArray(("hello-embeddedpg-fs-" * 2000).getBytes(StandardCharsets.UTF_8))
          val chunker = Chunker.fixed(UploadChunkSize(1024))

          for
            store    <- ZIO.service[BlobStore]
            written  <- Chunker.locally(chunker) {
                          ZStream.fromChunk(data).run(store.put(BlobWritePlan()))
                        }
            readBack <- store.get(written.key).runCollect
          yield assertTrue(readBack == data)
        },
        test("Postgres manifest proof rejects tampering before reconstruction") {
          val data = Chunk.fromArray(Array.tabulate(12_000)(index => (index % 241).toByte))

          ZIO.scoped {
            for
              ds        <- ZIO.service[javax.sql.DataSource]
              integrity <- makeManifestIntegrity
              root      <- ZIO.attemptBlocking(Files.createTempDirectory("graviton-pg-proof-blocks"))
              _         <- ZIO.addFinalizer(ZIO.attemptBlocking(deleteRecursive(root)).orDie)
              repo       = PgBlobManifestRepo.authenticated(ds, integrity)
              store      = new CasBlobStore(new FsBlockStore(root), repo)
              written   <- ZStream.fromChunk(data).run(store.put())
              clean     <- store.get(written.key).runCollect
              _         <- tamperManifestSpan(ds, written.key)
              failed    <- store.get(written.key).runHead.exit
            yield assertTrue(
              clean == data,
              failed.causeOption.flatMap(_.failureOption).exists(_.isInstanceOf[StoreError.CorruptData]),
            )
          }
        },
        test("late ranges select only intersecting Postgres manifest rows") {
          val data   = Chunk.fromArray(Array.tabulate(4096)(index => (index % 251).toByte))
          val start  = 3L * 1024L + 100L
          val length = 32L

          for
            store   <- ZIO.service[BlobStore]
            written <- Chunker.locally(Chunker.fixed(UploadChunkSize(1024))) {
                         ZStream.fromChunk(data).run(store.put())
                       }
            ds      <- ZIO.service[javax.sql.DataSource]
            repo     = new PgBlobManifestRepo(ds)
            refs    <- repo
                         .streamBlockRefsRange(
                           written.key,
                           BlobOffset.unsafe(start),
                           FileSize.unsafe(length),
                         )
                         .runCollect
            bytes   <- store
                         .getRange(
                           written.key,
                           BlobOffset.unsafe(start),
                           FileSize.unsafe(length),
                         )
                         .runCollect
          yield assertTrue(
            refs.length == 1,
            refs.head.offset.value == 3L * 1024L,
            bytes == data.slice(start.toInt, (start + length).toInt),
          )
        },
        test("stat returns real ingestion timestamp and correct size") {
          val data    = Chunk.fromArray(("stat-test-" * 500).getBytes(StandardCharsets.UTF_8))
          val chunker = Chunker.fixed(UploadChunkSize(1024))

          for
            before  <- Clock.instant
            store   <- ZIO.service[BlobStore]
            written <- Chunker.locally(chunker) {
                         ZStream.fromChunk(data).run(store.put(BlobWritePlan()))
                       }
            after   <- Clock.instant
            statOpt <- store.stat(written.key)
          yield assertTrue(
            statOpt.isDefined,
            statOpt.get.size.value == data.length.toLong,
            !statOpt.get.lastModified.isBefore(before),
            !statOpt.get.lastModified.isAfter(after),
          )
        },
        test("manifest spans are contiguous and cover the full blob") {
          val data    = Chunk.fromArray(("span-test-data-" * 300).getBytes(StandardCharsets.UTF_8))
          val chunker = Chunker.fixed(UploadChunkSize(1024))

          for
            store   <- ZIO.service[BlobStore]
            written <- Chunker.locally(chunker) {
                         ZStream.fromChunk(data).run(store.put(BlobWritePlan()))
                       }
            blobKey  = written.key
            ds      <- ZIO.service[javax.sql.DataSource]
            repo     = new PgBlobManifestRepo(ds)
            stored  <- repo.get(blobKey).someOrFail(new NoSuchElementException("manifest not found"))
            entries  = stored.manifest.entries
            spans    = entries.map(_.span)
          yield assertTrue(
            entries.nonEmpty,
            spans.head.startInclusive.value == 0L,
            spans.zip(spans.drop(1)).forall { case (a, b) =>
              b.startInclusive.value == a.endInclusive.value + 1L
            },
            stored.manifest.size == data.length.toLong,
          )
        },
        test("Postgres inventory and manifest inspection return persisted blobs") {
          val data = Chunk.fromArray(("inventory-test-" * 500).getBytes(StandardCharsets.UTF_8))

          for
            store     <- ZIO.service[BlobStore]
            written   <- ZStream.fromChunk(data).run(store.put(BlobWritePlan()))
            ds        <- ZIO.service[javax.sql.DataSource]
            repo       = new PgBlobManifestRepo(ds)
            inventory <- store.streamInventory.runCollect
            summaries <- repo.streamSummaries.runCollect
            details   <- store.inspect(written.key).someOrFail(new NoSuchElementException("manifest not found"))
          yield assertTrue(
            inventory.exists(_.key == written.key),
            summaries.exists(_._1 == written.key),
            details.listing.stat.size.value == data.length.toLong,
            details.blocks.nonEmpty,
            details.blocks.map(_.size).sum == data.length.toLong,
          )
        },
        test("Postgres streams and replaces manifests above the inspection limit") {
          val entryCount = BlobManifestRepo.MaxMaterializedEntries + 1
          val data       = Chunk.fill(entryCount)(1.toByte)

          for
            store           <- ZIO.service[BlobStore]
            ds              <- ZIO.service[javax.sql.DataSource]
            repo             = new PgBlobManifestRepo(ds)
            oneByte         <- Chunker.locally(Chunker.fixed(UploadChunkSize(1))) {
                                 ZStream.succeed(1.toByte).run(store.put())
                               }
            oneByteManifest <- repo.get(oneByte.key).someOrFail(new NoSuchElementException("one-byte manifest not found"))
            oneByteBlock     = oneByteManifest.manifest.entries.head.key.asInstanceOf[BinaryKey.Block]
            original        <- Chunker.locally(Chunker.fixed(UploadChunkSize.applyUnsafe(entryCount))) {
                                 ZStream.fromChunk(data).run(store.put())
                               }
            before          <- repo.getSummary(original.key).someOrFail(new NoSuchElementException("original summary not found"))
            entries          = ZStream.fromIterable(0 until entryCount).map { index =>
                                 val offset = BlobOffset.unsafe(index.toLong)
                                 ManifestEntry(oneByteBlock, Span.unsafe(offset, offset), Map.empty)
                               }
            now             <- Clock.instant
            _               <- repo.putStream(original.key, FileSize.unsafe(entryCount.toLong), entryCount, entries, now)
            after           <- repo.getSummary(original.key).someOrFail(new NoSuchElementException("updated summary not found"))
            refs            <- repo.streamBlockRefs(original.key).runCount
            bytes           <- store.get(original.key).runCount
            inspect         <- repo.get(original.key).exit
          yield assertTrue(
            before.blockCount == 1,
            after.blockCount == entryCount,
            refs == entryCount.toLong,
            bytes == entryCount.toLong,
            inspect.isFailure,
          )
        },
        test("Postgres manifest deletion removes the logical blob and is idempotent") {
          val data = Chunk.fromArray(("delete-test-" * 500).getBytes(StandardCharsets.UTF_8))

          for
            store        <- ZIO.service[BlobStore]
            ds           <- ZIO.service[javax.sql.DataSource]
            repo          = new PgBlobManifestRepo(ds)
            written      <- ZStream.fromChunk(data).run(store.put(BlobWritePlan()))
            before       <- store.stat(written.key)
            removed      <- repo.delete(written.key)
            after        <- store.stat(written.key)
            removedAgain <- repo.delete(written.key)
          yield assertTrue(before.nonEmpty, removed, after.isEmpty, !removedAgain)
        },
        test("Postgres replica index persists and replaces locator sets") {
          for
            store    <- ZIO.service[BlobStore]
            ds       <- ZIO.service[javax.sql.DataSource]
            written  <- ZStream.fromIterable("replica-index".getBytes(StandardCharsets.UTF_8)).run(store.put())
            index     = new PgReplicaIndex(ds)
            first     = BlobLocator(LocatorScheme.applyUnsafe("s3"), LocatorBucket.applyUnsafe("primary"), LocatorPath.applyUnsafe("objects/a"))
            second    = BlobLocator(LocatorScheme.applyUnsafe("fs"), LocatorBucket.applyUnsafe("local"), LocatorPath.applyUnsafe("blocks/a"))
            _        <- index.update(written.key, Set(first, second))
            both     <- index.replicas(written.key)
            _        <- index.update(written.key, Set(second))
            replaced <- index.replicas(written.key)
          yield assertTrue(both == Set(first, second), replaced == Set(second))
        },
        test("Postgres key/value backend persists typed, bounded values") {
          val key   = KvKey.applyUnsafe("integration/metadata/example")
          val value = KvValue.fromArray("schema-backed-value".getBytes(StandardCharsets.UTF_8)).toOption.get

          for
            ds      <- ZIO.service[javax.sql.DataSource]
            store    = new PgKeyValueStore(ds)
            _       <- store.put(key, value)
            loaded  <- store.get(key)
            _       <- store.delete(key)
            deleted <- store.get(key)
          yield assertTrue(loaded.contains(value), deleted.isEmpty)
        },
        test("Postgres object backend streams multi-chunk objects, copies, lists, and deletes") {
          val source = BlobLocator(
            LocatorScheme.applyUnsafe("pg"),
            LocatorBucket.applyUnsafe("integration"),
            LocatorPath.applyUnsafe("objects/source"),
          )
          val copy   = source.copy(path = LocatorPath.applyUnsafe("objects/copy"))
          val data   = Chunk.fromIterable(0 until (3 * 1024 * 1024 + 37)).map(index => (index % 251).toByte)

          for
            ds      <- ZIO.service[javax.sql.DataSource]
            store    = new PgMutableObjectStore(ds)
            _       <- ZStream.fromChunk(data).rechunk(73 * 1024 + 11).run(store.put(source))
            size    <- store.head(source)
            loaded  <- store.get(source).runCollect
            _       <- store.copy(source, copy)
            copied  <- store.get(copy).runCollect
            listed  <- store.list("pg://integration/objects/").runCollect
            _       <- store.delete(source)
            deleted <- store.head(source)
          yield assertTrue(
            size.contains(data.length.toLong),
            loaded == data,
            copied == data,
            listed.toSet == Set(source, copy),
            deleted.isEmpty,
          )
        },
        test("Postgres object upload rolls back partial chunks on stream failure") {
          val locator = BlobLocator(
            LocatorScheme.applyUnsafe("pg"),
            LocatorBucket.applyUnsafe("integration"),
            LocatorPath.applyUnsafe("objects/interrupted"),
          )
          val failure = new RuntimeException("intentional upstream failure")

          for
            ds   <- ZIO.service[javax.sql.DataSource]
            store = new PgMutableObjectStore(ds)
            exit <- (ZStream.fromChunk(Chunk.fill(2 * 1024 * 1024)(1.toByte)) ++ ZStream.fail(failure))
                      .run(store.put(locator))
                      .exit
            head <- store.head(locator)
          yield assertTrue(exit.isFailure, head.isEmpty)
        },
        test("PostgreSQL resumable ledger resumes across service instances and commits staged objects") {
          val sessionKey = UploadSessionKey(
            TenantId.applyUnsafe("9f2f172c-8e6b-4aef-8be8-4c750420d971"),
            UploadSessionId.applyUnsafe("ab573594-abaa-44fa-867a-8c733bf87f6c"),
          )
          val firstPart  = UploadPartId.applyUnsafe("11111111-1111-4111-8111-111111111111")
          val secondPart = UploadPartId.applyUnsafe("22222222-2222-4222-8222-222222222222")
          val bytes      = Chunk(1, 2, 3, 4, 5, 6).map(_.toByte)

          for
            ds        <- ZIO.service[javax.sql.DataSource]
            blobStore <- ZIO.service[BlobStore]
            target    <- ZIO.fromEither(UploadStagingTarget.from("pg", "integration"))
            staging    = new PgMutableObjectStore(ds)
            first      = new ResumableUploadService(new PgResumableUploadRepository(ds), staging, target)
            _         <- first.create(
                           sessionKey,
                           UploadIntent(MediaTypes.application.`octet-stream`, Some(FileSize.applyUnsafe(6L))),
                         )
            appended  <- first.append(
                           sessionKey,
                           firstPart,
                           UploadOffset.applyUnsafe(0L),
                           Some(FileSize.applyUnsafe(3L)),
                           ZStream.fromChunk(bytes.take(3)),
                         )
            restarted  = new ResumableUploadService(new PgResumableUploadRepository(ds), staging, target)
            status    <- restarted.status(sessionKey)
            _         <- restarted.append(
                           sessionKey,
                           secondPart,
                           UploadOffset.applyUnsafe(3L),
                           Some(FileSize.applyUnsafe(3L)),
                           ZStream.fromChunk(bytes.drop(3)),
                         )
            committed <- restarted.commit(sessionKey) { (_, stream) =>
                           stream.run(blobStore.put()).map(_.key)
                         }
            roundTrip <- blobStore.get(committed.blob).runCollect
            cleaned   <- staging
                           .head(appended.part.locator)
                           .repeatUntil(_.isEmpty)
                           .timeoutFail(new IllegalStateException("PostgreSQL staging cleanup did not finish"))(2.seconds)
          yield assertTrue(
            status.offset.value == 3L,
            roundTrip == bytes,
            cleaned.isEmpty,
          )
        },
        test("PostgreSQL advisory locks coordinate independent server instances") {
          val config = MaintenanceConfig(
            namespace = RepositoryNamespace.applyUnsafe("embedded-pg-shared"),
            acquisitionTimeout = 2.seconds,
            pollInterval = 50.millis,
          )

          for
            ds                 <- ZIO.service[javax.sql.DataSource]
            first              <- PgMaintenanceCoordinator.make(ds, config)
            second             <- PgMaintenanceCoordinator.make(ds, config)
            operationEntered   <- Promise.make[Nothing, Unit]
            releaseOperation   <- Promise.make[Nothing, Unit]
            maintenanceStarted <- Promise.make[Nothing, Unit]
            maintenanceEntered <- Promise.make[Nothing, Unit]
            operation          <- ZIO
                                    .scoped(first.operationPermit *> operationEntered.succeed(()) *> releaseOperation.await)
                                    .fork
            _                  <- operationEntered.await
            concurrentShared   <- second.withOperation(ZIO.succeed(true))
            maintenance        <- (maintenanceStarted.succeed(()) *>
                                    ZIO.scoped(second.maintenanceLease *> maintenanceEntered.succeed(()))).fork
            _                  <- maintenanceStarted.await
            _                  <- TestClock.adjust(1.millis)
            enteredBeforeEnd   <- maintenanceEntered.isDone
            _                  <- releaseOperation.succeed(())
            _                  <- operation.join
            _                  <- TestClock.adjust(100.millis)
            _                  <- maintenance.join
          yield assertTrue(concurrentShared, !enteredBeforeEnd)
        },
        test("one coordinator coalesces hundreds of tenant operations onto one leased connection") {
          val config = MaintenanceConfig(
            namespace = RepositoryNamespace.applyUnsafe("embedded-pg-tenant-cell"),
            acquisitionTimeout = 2.seconds,
            pollInterval = 50.millis,
          )

          for
            ds          <- ZIO.service[javax.sql.DataSource]
            coordinator <- PgMaintenanceCoordinator.make(ds, config)
            entered     <- Ref.make(0)
            allEntered  <- Promise.make[Nothing, Unit]
            release     <- Promise.make[Nothing, Unit]
            fibers      <- ZIO.foreach(1 to 256) { _ =>
                             ZIO
                               .scoped(
                                 coordinator.operationPermit *>
                                   entered.updateAndGet(_ + 1).flatMap(count => allEntered.succeed(()).when(count == 256)) *>
                                   release.await
                               )
                               .fork
                           }
            _           <- allEntered.await
            pooled      <- ZIO
                             .fromOption(PgDataSource.poolStats(ds))
                             .orElseFail(new IllegalStateException("expected a pooled PostgreSQL data source"))
            _           <- release.succeed(())
            _           <- ZIO.foreachDiscard(fibers)(_.join)
          yield assertTrue(
            pooled.activeConnections == 1,
            pooled.awaitingConnection == 0,
          )
        },
        test("interrupting PostgreSQL lock acquisition closes the waiting lease") {
          val config = MaintenanceConfig(
            namespace = RepositoryNamespace.applyUnsafe("embedded-pg-interrupt"),
            acquisitionTimeout = 2.seconds,
            pollInterval = 50.millis,
          )

          for
            ds               <- ZIO.service[javax.sql.DataSource]
            holder           <- PgMaintenanceCoordinator.make(ds, config)
            waiter           <- PgMaintenanceCoordinator.make(ds, config)
            operationEntered <- Promise.make[Nothing, Unit]
            releaseOperation <- Promise.make[Nothing, Unit]
            waitingStarted   <- Promise.make[Nothing, Unit]
            operation        <- ZIO
                                  .scoped(holder.operationPermit *> operationEntered.succeed(()) *> releaseOperation.await)
                                  .fork
            _                <- operationEntered.await
            waiting          <- (waitingStarted.succeed(()) *> ZIO.scoped(waiter.maintenanceLease)).fork
            _                <- waitingStarted.await
            _                <- TestClock.adjust(1.millis)
            interrupted      <- waiting.interrupt
            _                <- releaseOperation.succeed(())
            _                <- operation.join
            reacquired       <- waiter.withMaintenance(ZIO.succeed(true))
          yield assertTrue(interrupted.isInterrupted, reacquired)
        },
        test("PostgreSQL catalog shares folder references without owning CAS content") {
          val bytes = Chunk.fromArray("catalog-cas-reference".getBytes(StandardCharsets.UTF_8))

          for
            ds       <- ZIO.service[javax.sql.DataSource]
            store    <- ZIO.service[BlobStore]
            written  <- ZStream.fromChunk(bytes).run(store.put())
            catalog   = new PgCatalog(ds)
            folder   <- catalog.createFolder(None, CatalogName.parse("Research").toOption.get)
            file     <- catalog.attachFile(
                          Some(folder.id),
                          CatalogName.parse("evidence.bin").toOption.get,
                          written.key,
                          MediaTypes.application.`octet-stream`,
                          written.stats,
                        )
            listing  <- catalog.list(Some(folder.id))
            nonEmpty <- catalog.removeFolder(folder.id).exit
            _        <- catalog.removeFile(file.id)
            _        <- catalog.removeFolder(folder.id)
            retained <- store.get(written.key).runCollect
          yield assertTrue(
            listing.files.map(_.id) == Chunk(file.id),
            nonEmpty.causeOption.flatMap(_.failureOption).exists(_.isInstanceOf[CatalogError.FolderNotEmpty]),
            retained == bytes,
          )
        },
        test("PostgreSQL tenant policies isolate manifests and share blocks only by explicit domain") {
          val isolatedA = TenantId.applyUnsafe("10000000-0000-4000-8000-000000000001")
          val isolatedB = TenantId.applyUnsafe("10000000-0000-4000-8000-000000000002")
          val sharedA   = TenantId.applyUnsafe("20000000-0000-4000-8000-000000000001")
          val sharedB   = TenantId.applyUnsafe("20000000-0000-4000-8000-000000000002")
          val remote    = TenantId.applyUnsafe("30000000-0000-4000-8000-000000000003")
          val bytes     = Chunk.fromArray(Array.tabulate(8192)(index => (index % 251).toByte))

          ZIO.scoped {
            for
              ds              <- ZIO.service[javax.sql.DataSource]
              _               <- insertTenantPolicy(ds, isolatedA, None)
              _               <- insertTenantPolicy(ds, isolatedB, None)
              _               <- insertTenantPolicy(ds, sharedA, Some("research-consortium"))
              _               <- insertTenantPolicy(ds, sharedB, Some("research-consortium"))
              _               <- insertTenantPolicy(ds, remote, None, "private-cell")
              isolatedRootA   <- temporaryDirectory("graviton-tenant-a")
              isolatedRootB   <- temporaryDirectory("graviton-tenant-b")
              sharedRoot      <- temporaryDirectory("graviton-tenant-shared")
              allowedCatalog   = new PgTenantPolicyCatalog(ds, TenantStorageConfig(allowSharedDeduplication = true))
              deniedCatalog    = new PgTenantPolicyCatalog(ds, TenantStorageConfig(allowSharedDeduplication = false))
              remoteCatalog    = new PgTenantPolicyCatalog(
                                   ds,
                                   TenantStorageConfig(allowSharedDeduplication = false),
                                   TenantCellId.applyUnsafe("private-cell"),
                                 )
              isolatedPolicyA <- allowedCatalog.resolve(isolatedA)
              isolatedPolicyB <- allowedCatalog.resolve(isolatedB)
              sharedPolicyA   <- allowedCatalog.resolve(sharedA)
              sharedPolicyB   <- allowedCatalog.resolve(sharedB)
              deniedShared    <- deniedCatalog.resolve(sharedA).exit
              hiddenCell      <- allowedCatalog.resolve(remote).exit
              remotePolicy    <- remoteCatalog.resolve(remote)
              storeA           = new CasBlobStore(
                                   new FsBlockStore(isolatedRootA),
                                   new PgTenantBlobManifestRepo(ds, isolatedA, isolatedPolicyA.route.storageDomain),
                                 )
              storeB           = new CasBlobStore(
                                   new FsBlockStore(isolatedRootB),
                                   new PgTenantBlobManifestRepo(ds, isolatedB, isolatedPolicyB.route.storageDomain),
                                 )
              storeSharedA     = new CasBlobStore(
                                   new FsBlockStore(sharedRoot),
                                   new PgTenantBlobManifestRepo(ds, sharedA, sharedPolicyA.route.storageDomain),
                                 )
              storeSharedB     = new CasBlobStore(
                                   new FsBlockStore(sharedRoot),
                                   new PgTenantBlobManifestRepo(ds, sharedB, sharedPolicyB.route.storageDomain),
                                 )
              isolatedWriteA  <- Chunker.locally(Chunker.fixed(UploadChunkSize(1024))) {
                                   ZStream.fromChunk(bytes).run(storeA.put())
                                 }
              invisibleToB    <- storeB.stat(isolatedWriteA.key)
              isolatedWriteB  <- Chunker.locally(Chunker.fixed(UploadChunkSize(1024))) {
                                   ZStream.fromChunk(bytes).run(storeB.put())
                                 }
              sharedWriteA    <- Chunker.locally(Chunker.fixed(UploadChunkSize(1024))) {
                                   ZStream.fromChunk(bytes).run(storeSharedA.put())
                                 }
              invisibleShared <- storeSharedB.stat(sharedWriteA.key)
              sharedWriteB    <- Chunker.locally(Chunker.fixed(UploadChunkSize(1024))) {
                                   ZStream.fromChunk(bytes).run(storeSharedB.put())
                                 }
              _               <- storeSharedA.delete(sharedWriteA.key)
              retainedForB    <- storeSharedB.get(sharedWriteB.key).runCollect
              _               <- updateTenantLifecycle(ds, isolatedA, "suspended")
              revisedPolicy   <- allowedCatalog.resolve(isolatedA)
            yield assertTrue(
              isolatedPolicyA.route.deduplication == DeduplicationScope.Isolated,
              isolatedPolicyB.route.deduplication == DeduplicationScope.Isolated,
              sharedPolicyA.route.storageDomain == sharedPolicyB.route.storageDomain,
              deniedShared.causeOption.flatMap(_.failureOption).exists(_.isInstanceOf[TenantRoutingError.InvalidPolicy]),
              hiddenCell.causeOption.flatMap(_.failureOption).exists(_.isInstanceOf[TenantRoutingError.UnknownTenant]),
              remotePolicy.route.tenantId == remote,
              invisibleToB.isEmpty,
              isolatedWriteA.stats.freshBlocks == isolatedWriteA.stats.blockCount,
              isolatedWriteB.stats.freshBlocks == isolatedWriteB.stats.blockCount,
              invisibleShared.isEmpty,
              sharedWriteB.stats.duplicateBlocks == sharedWriteB.stats.blockCount,
              retainedForB == bytes,
              revisedPolicy.lifecycle == TenantLifecycle.Suspended,
              revisedPolicy.revision.value == 1L,
            )
          }
        },
        test("tenant domain snapshots are durable, deterministic, and streamed by domain") {
          val cell     = TenantCellId.applyUnsafe("snapshot-cell")
          val isolated = TenantId.applyUnsafe("70000000-0000-4000-8000-000000000001")
          val sharedA  = TenantId.applyUnsafe("70000000-0000-4000-8000-000000000002")
          val sharedB  = TenantId.applyUnsafe("70000000-0000-4000-8000-000000000003")
          for
            ds       <- ZIO.service[javax.sql.DataSource]
            _        <- insertTenantPolicy(ds, isolated, None, cell.value)
            _        <- insertTenantPolicy(ds, sharedA, Some("snapshot-shared"), cell.value)
            _        <- insertTenantPolicy(ds, sharedB, Some("snapshot-shared"), cell.value)
            snapshots = new PgTenantDomainSnapshot(ds)
            first    <- snapshots.capture(cell)
            domains  <- snapshots.streamDomains(first.snapshotId).runCollect
            epochA   <- snapshots.beginRepairEpoch("snapshot-domain-test", first.membershipSha256)
            epochB   <- snapshots.beginRepairEpoch("snapshot-domain-test", first.membershipSha256)
            second   <- snapshots.capture(cell)
            _        <- writeRepairCursor(ds, "snapshot-domain-test", 42L)
            _        <- bumpTenantRevision(ds, sharedA)
            changed  <- snapshots.capture(cell)
            epochC   <- snapshots.beginRepairEpoch("snapshot-domain-test", changed.membershipSha256)
            cursor   <- readRepairCursor(ds, "snapshot-domain-test")
            latest   <- snapshots.latest(cell)
            removed  <- snapshots.retainLatest(cell, 1)
          yield assertTrue(
            first.memberCount == 3L,
            first.membershipSha256.length == 64,
            second.membershipSha256 == first.membershipSha256,
            epochA,
            !epochB,
            changed.membershipSha256 != first.membershipSha256,
            epochC,
            cursor.isEmpty,
            domains.map(_.value).toSet == Set(s"tenant:${isolated.value}", "shared:snapshot-shared"),
            latest.exists(_.snapshotId == changed.snapshotId),
            removed == 2L,
          )
        },
        test("retained-byte quota is idempotent, released on delete, and atomic across writers") {
          val tenant = TenantId.applyUnsafe("40000000-0000-4000-8000-000000000004")
          val first  = Chunk.fromArray("aaaaaaaa".getBytes(StandardCharsets.UTF_8))
          val second = Chunk.fromArray("bbbbbbbb".getBytes(StandardCharsets.UTF_8))
          val third  = Chunk.fromArray("cccccccc".getBytes(StandardCharsets.UTF_8))

          ZIO.scoped {
            for
              ds        <- ZIO.service[javax.sql.DataSource]
              _         <- insertTenantPolicy(ds, tenant, None, maxRetainedBytes = 12L)
              root      <- temporaryDirectory("graviton-tenant-quota")
              catalog    = new PgTenantPolicyCatalog(ds, TenantStorageConfig(allowSharedDeduplication = false))
              policy    <- catalog.resolve(tenant)
              storeA     = new CasBlobStore(
                             new FsBlockStore(root),
                             new PgTenantBlobManifestRepo(ds, tenant, policy.route.storageDomain),
                           )
              storeB     = new CasBlobStore(
                             new FsBlockStore(root),
                             new PgTenantBlobManifestRepo(ds, tenant, policy.route.storageDomain),
                           )
              written   <- ZStream.fromChunk(first).run(storeA.put())
              duplicate <- ZStream.fromChunk(first).run(storeB.put())
              rejected  <- ZStream.fromChunk(second).run(storeB.put()).exit
              before    <- readTenantUsage(ds, tenant)
              _         <- storeA.delete(written.key)
              removed   <- storeA.stat(written.key)
              released  <- readTenantUsage(ds, tenant)
              raced     <- ZIO.collectAllPar(
                             Chunk(
                               ZStream.fromChunk(second).run(storeA.put()).exit,
                               ZStream.fromChunk(third).run(storeB.put()).exit,
                             )
                           )
              after     <- readTenantUsage(ds, tenant)
            yield assertTrue(
              duplicate.key == written.key,
              rejected.foldExit(
                _.failureOption.exists(_.isInstanceOf[StoreError.TenantStorageQuotaExceeded]),
                _ => false,
              ),
              before == (8L, 1L),
              removed.isEmpty,
              released == (0L, 0L),
              raced.count(_.isSuccess) == 1,
              raced.count(_.isFailure) == 1,
              raced
                .filter(_.isFailure)
                .forall(
                  _.causeOption.flatMap(_.failureOption).exists(_.isInstanceOf[StoreError.TenantStorageQuotaExceeded])
                ),
              after == (8L, 1L),
            )
          }
        },
        test("bounded PostgreSQL pool resolves hundreds of tenant policies concurrently") {
          val tenants = Chunk.fromIterable((1 to 256).map { index =>
            TenantId.applyUnsafe(f"50000000-0000-4000-8000-$index%012x")
          })
          for
            ds       <- ZIO.service[javax.sql.DataSource]
            _        <- ZIO.foreachParDiscard(tenants)(tenant => insertTenantPolicy(ds, tenant, None))
            raw       = new PgTenantPolicyCatalog(ds, TenantStorageConfig(allowSharedDeduplication = false))
            catalog  <- TenantPolicyCatalog.cached(raw, maximumEntries = 512, timeToLive = 1.minute)
            policies <- ZIO.foreachPar(tenants)(catalog.resolve)
            pooled   <- ZIO
                          .fromOption(PgDataSource.poolStats(ds))
                          .orElseFail(new IllegalStateException("expected a pooled PostgreSQL data source"))
          yield assertTrue(
            policies.map(_.route.tenantId).toSet == tenants.toSet,
            policies.forall(_.route.deduplication == DeduplicationScope.Isolated),
            pooled.totalConnections <= pooled.maximumPoolSize,
            pooled.awaitingConnection == 0,
          )
        },
        test("forced row-level security hides another tenant from a restricted runtime role") {
          val tenantA = TenantId.applyUnsafe("60000000-0000-4000-8000-000000000001")
          val tenantB = TenantId.applyUnsafe("60000000-0000-4000-8000-000000000002")
          for
            ds       <- ZIO.service[javax.sql.DataSource]
            _        <- insertTenantPolicy(ds, tenantA, None)
            _        <- insertTenantPolicy(ds, tenantB, None)
            observed <- ZIO.attemptBlocking {
                          val connection = ds.getConnection
                          connection.setAutoCommit(false)
                          try
                            val setup = connection.createStatement()
                            try
                              setup.execute("CREATE ROLE graviton_rls_probe NOLOGIN NOSUPERUSER NOBYPASSRLS")
                              setup.execute("GRANT USAGE ON SCHEMA core, graviton TO graviton_rls_probe")
                              setup.execute(
                                "GRANT SELECT, INSERT, UPDATE, DELETE ON graviton.tenant_storage_usage TO graviton_rls_probe"
                              )
                              setup.execute("SET LOCAL ROLE graviton_rls_probe")
                              setup.execute(s"SELECT set_config('app.org_id', '${tenantA.value}', true)")
                              setup.execute(s"INSERT INTO graviton.tenant_storage_usage (tenant_id) VALUES ('${tenantA.value}')")
                              setup.execute(s"SELECT set_config('app.org_id', '${tenantB.value}', true)")
                              val rows     = setup.executeQuery(
                                s"SELECT count(*) FROM graviton.tenant_storage_usage WHERE tenant_id = '${tenantA.value}'"
                              )
                              val hidden   =
                                try rows.next() && rows.getLong(1) == 0L
                                finally rows.close()
                              val rejected =
                                try
                                  setup.execute(s"INSERT INTO graviton.tenant_storage_usage (tenant_id) VALUES ('${tenantA.value}')")
                                  false
                                catch case _: java.sql.SQLException => true
                              hidden -> rejected
                            finally setup.close()
                          finally
                            connection.rollback()
                            connection.close()
                        }
          yield assertTrue(observed == (true -> true))
        },
        test("JDBC audit sink persists one linear chain under concurrent writes") {
          val orgId       = UUID.fromString("00000000-0000-0000-0000-000000000101")
          val principalId = UUID.fromString("00000000-0000-0000-0000-000000000102")
          val caller      = CallerContext(
            orgId = orgId,
            principalId = principalId,
            capabilities = CapabilitySet.of(Capability.BlobRead),
            jti = "embedded-pg-audit",
            tokenExpiresAt = Instant.parse("2099-01-01T00:00:00Z"),
            requestId = UUID.fromString("00000000-0000-0000-0000-000000000103"),
          )

          for
            ds    <- ZIO.service[javax.sql.DataSource]
            audit <- ZIO.service[AuditSink].provide(ZLayer.succeed(ds), AuditSink.jdbc)
            _     <- CallerContext.scopedWith(caller) {
                       ZIO.foreachParDiscard(1 to 20) { index =>
                         audit.record(
                           AuditEvent(
                             action = s"blob.read.$index",
                             resource = ResourceRef.blob(s"sha-256:${"a" * 64}:$index"),
                             outcome = AuditEvent.Outcome.Allow,
                             bytes = Some(index.toLong),
                           )
                         )
                       }
                     }
            chain <- ZIO.attemptBlocking(readAuditChain(ds, orgId))
          yield assertTrue(
            chain.map(_._1) == (1L to 20L).toVector,
            chain.headOption.exists(row => java.util.Arrays.equals(row._2, new Array[Byte](32))),
            chain.zip(chain.drop(1)).forall { case (previous, next) =>
              java.util.Arrays.equals(next._2, previous._3)
            },
          )
        },
      ).provideShared(blobStoreLayer) @@ TestAspect.sequential

  private val ddlRelPath: Path =
    Path.of("modules/backend/graviton-pg/src/main/resources/db/migration/V001__graviton.sql")

  private def resolveDdlPath: IO[Throwable, Path] =
    val roots: List[Path] =
      List(
        sys.env.get("GITHUB_WORKSPACE").map(Path.of(_)),
        sys.props.get("user.dir").map(Path.of(_)),
        Some(Path.of(".")),
      ).flatten.map(_.toAbsolutePath.normalize()).distinct

    val candidates: List[Path] =
      roots.flatMap { root =>
        Iterator
          .iterate(root)(p => Option(p.getParent).getOrElse(p))
          .take(10)
          .map(_.resolve(ddlRelPath))
          .toList
      }.distinct

    ZIO
      .fromOption(candidates.find(Files.exists(_)))
      .orElseFail(new FileNotFoundException(s"Could not locate DDL at '${ddlRelPath.toString}' (tried: ${candidates.mkString(", ")})"))

  private def executeSqlFile(connection: Connection, file: Path): Unit =
    val sql = Files.readString(file)
    splitStatements(sql).foreach { stmt =>
      val s = connection.createStatement()
      try s.execute(stmt)
      finally s.close()
    }

  private def readAuditChain(dataSource: javax.sql.DataSource, orgId: UUID): Vector[(Long, Array[Byte], Array[Byte])] =
    val connection = dataSource.getConnection
    try
      val statement = connection.prepareStatement(
        "SELECT seq, prev_hash, row_hash FROM graviton.audit_log WHERE org_id = ? ORDER BY seq"
      )
      try
        statement.setObject(1, orgId)
        val result = statement.executeQuery()
        try
          val rows = Vector.newBuilder[(Long, Array[Byte], Array[Byte])]
          while result.next() do rows += ((result.getLong(1), result.getBytes(2), result.getBytes(3)))
          rows.result()
        finally result.close()
      finally statement.close()
    finally connection.close()

  private def makeManifestIntegrity: Task[ManifestIntegrity] =
    for
      keyId   <- ZIO.fromEither(ManifestKeyId.either("embedded-pg-test")).mapError(new IllegalArgumentException(_))
      hmacKey <- ZIO
                   .fromEither(ManifestKeyService.HmacKey.fromBytes(Array.tabulate[Byte](32)(index => (index + 7).toByte)))
                   .mapError(new IllegalArgumentException(_))
      service <- ZIO
                   .fromEither(ManifestKeyService.hmac(keyId, Map(keyId -> hmacKey)))
                   .mapError(new IllegalArgumentException(_))
    yield ManifestIntegrity(service)

  private def tamperManifestSpan(dataSource: javax.sql.DataSource, blob: BinaryKey.Blob): Task[Unit] =
    ZIO.attemptBlocking {
      val connection = dataSource.getConnection
      try
        val statement = connection.prepareStatement(
          """UPDATE graviton.blob_block
            |SET block_length = block_length - 1
            |WHERE alg = 'sha256'::core.hash_alg AND hash_bytes = ? AND byte_length = ?
            |  AND ordinal = (
            |    SELECT max(ordinal) FROM graviton.blob_block
            |    WHERE alg = 'sha256'::core.hash_alg AND hash_bytes = ? AND byte_length = ?
            |  )
            |""".stripMargin
        )
        try
          statement.setBytes(1, blob.bits.digest.toInteropArray)
          statement.setLong(2, blob.bits.size)
          statement.setBytes(3, blob.bits.digest.toInteropArray)
          statement.setLong(4, blob.bits.size)
          val updated = statement.executeUpdate()
          if updated != 1 then throw new IllegalStateException(s"expected one manifest proof row, updated $updated")
        finally statement.close()
      finally connection.close()
    }

  private def insertTenantPolicy(
    dataSource: javax.sql.DataSource,
    tenantId: TenantId,
    deduplicationDomain: Option[String],
    cellId: String = "default",
    maxRetainedBytes: Long = 1125899906842624L,
  ): Task[Unit] =
    ZIO.attemptBlocking {
      val connection = dataSource.getConnection
      try
        val statement = connection.prepareStatement(
          """INSERT INTO graviton.tenant_storage_policy (
            |  tenant_id, cell_id, lifecycle, deduplication_domain, max_concurrent_operations, max_object_bytes, max_retained_bytes
            |) VALUES (?::uuid, ?, 'active', ?, 8, 1048576, ?)""".stripMargin
        )
        try
          statement.setString(1, tenantId.value)
          statement.setString(2, cellId)
          deduplicationDomain match
            case Some(value) => statement.setString(3, value)
            case None        => statement.setNull(3, java.sql.Types.VARCHAR)
          statement.setLong(4, maxRetainedBytes)
          statement.executeUpdate()
          ()
        finally statement.close()
      finally connection.close()
    }

  private def bumpTenantRevision(dataSource: javax.sql.DataSource, tenantId: TenantId): Task[Unit] =
    ZIO.attemptBlocking {
      val connection = dataSource.getConnection
      try
        val statement = connection.prepareStatement(
          "UPDATE graviton.tenant_storage_policy SET revision = revision + 1 WHERE tenant_id = ?::uuid"
        )
        try
          statement.setString(1, tenantId.value)
          if statement.executeUpdate() != 1 then throw new IllegalStateException("tenant policy was not updated")
        finally statement.close()
      finally connection.close()
    }

  private def writeRepairCursor(dataSource: javax.sql.DataSource, namespace: String, offset: Long): Task[Unit] =
    ZIO.attemptBlocking {
      val connection = dataSource.getConnection
      try
        val statement = connection.prepareStatement(
          "INSERT INTO graviton.repair_state(namespace, next_offset) VALUES (?, ?)"
        )
        try
          statement.setString(1, namespace)
          statement.setLong(2, offset)
          statement.executeUpdate()
          ()
        finally statement.close()
      finally connection.close()
    }

  private def readRepairCursor(dataSource: javax.sql.DataSource, namespace: String): Task[Option[Long]] =
    ZIO.attemptBlocking {
      val connection = dataSource.getConnection
      try
        val statement = connection.prepareStatement(
          "SELECT next_offset FROM graviton.repair_state WHERE namespace = ?"
        )
        try
          statement.setString(1, namespace)
          val result = statement.executeQuery()
          try if result.next() then Some(result.getLong(1)) else None
          finally result.close()
        finally statement.close()
      finally connection.close()
    }

  private def readTenantUsage(dataSource: javax.sql.DataSource, tenantId: TenantId): Task[(Long, Long)] =
    ZIO.attemptBlocking {
      val connection = dataSource.getConnection
      try
        val statement = connection.prepareStatement(
          "SELECT retained_bytes, blob_count FROM graviton.tenant_storage_usage WHERE tenant_id = ?::uuid"
        )
        try
          statement.setString(1, tenantId.value)
          val rows = statement.executeQuery()
          try
            if !rows.next() then throw new IllegalStateException(s"tenant usage ${tenantId.value} was not found")
            rows.getLong(1) -> rows.getLong(2)
          finally rows.close()
        finally statement.close()
      finally connection.close()
    }

  private def updateTenantLifecycle(
    dataSource: javax.sql.DataSource,
    tenantId: TenantId,
    lifecycle: String,
  ): Task[Unit] =
    ZIO.attemptBlocking {
      val connection = dataSource.getConnection
      try
        val statement = connection.prepareStatement(
          "UPDATE graviton.tenant_storage_policy SET lifecycle = ? WHERE tenant_id = ?::uuid"
        )
        try
          statement.setString(1, lifecycle)
          statement.setString(2, tenantId.value)
          if statement.executeUpdate() != 1 then throw new IllegalStateException(s"tenant ${tenantId.value} was not updated")
        finally statement.close()
      finally connection.close()
    }

  private def temporaryDirectory(prefix: String): ZIO[Scope, Throwable, Path] =
    ZIO.acquireRelease(ZIO.attemptBlocking(Files.createTempDirectory(prefix)))(path => ZIO.attemptBlocking(deleteRecursive(path)).orDie)

  /** Minimal SQL splitter (handles $$ blocks) */
  private def splitStatements(sql: String): Seq[String] =
    val statements = ArrayBuffer.newBuilder[String]
    val current    = new StringBuilder
    var idx        = 0
    var inSingle   = false
    var inDouble   = false
    var dollarTag  = Option.empty[String]

    def startsWith(tag: String, offset: Int): Boolean =
      sql.regionMatches(offset, tag, 0, tag.length)

    while idx < sql.length do
      if dollarTag.nonEmpty then
        val tag = dollarTag.get
        if startsWith(tag, idx) then
          current.append(tag)
          idx += tag.length
          dollarTag = None
        else
          current.append(sql.charAt(idx))
          idx += 1
      else if inSingle then
        val ch = sql.charAt(idx)
        current.append(ch)
        if ch == '\'' && (idx == 0 || sql.charAt(idx - 1) != '\\') then inSingle = false
        idx += 1
      else if inDouble then
        val ch = sql.charAt(idx)
        current.append(ch)
        if ch == '"' && (idx == 0 || sql.charAt(idx - 1) != '\\') then inDouble = false
        idx += 1
      else if startsWith("--", idx) then
        val end = sql.indexOf('\n', idx)
        if end == -1 then
          current.append(sql.substring(idx))
          idx = sql.length
        else
          current.append(sql.substring(idx, end + 1))
          idx = end + 1
      else if startsWith("/*", idx) then
        val end  = sql.indexOf("*/", idx + 2)
        val stop = if end == -1 then sql.length else end + 2
        current.append(sql.substring(idx, stop))
        idx = stop
      else
        val ch = sql.charAt(idx)
        ch match
          case '\''  =>
            inSingle = true
            current.append(ch)
            idx += 1
          case '"'   =>
            inDouble = true
            current.append(ch)
            idx += 1
          case '$'   =>
            val tag = extractDollarTag(sql, idx)
            if tag.nonEmpty then
              dollarTag = Some(tag)
              current.append(tag)
              idx += tag.length
            else
              current.append(ch)
              idx += 1
          case ';'   =>
            val statement = current.toString.trim
            if statement.nonEmpty then statements += statement
            current.clear()
            idx += 1
          case other =>
            current.append(other)
            idx += 1

    val tail = current.toString.trim
    if tail.nonEmpty then statements += tail
    statements.result().toSeq

  private def extractDollarTag(sql: String, start: Int): String =
    var end = start + 1
    while end < sql.length && {
        val ch = sql.charAt(end)
        ch.isLetterOrDigit || ch == '_'
      }
    do end += 1
    if end < sql.length && sql.charAt(end) == '$' then sql.substring(start, end + 1)
    else ""

  private def deleteRecursive(path: Path): Unit =
    if Files.notExists(path) then ()
    else
      if Files.isDirectory(path) then
        val dir = Files.newDirectoryStream(path)
        try dir.forEach(p => deleteRecursive(p))
        finally dir.close()
      val _ = Files.deleteIfExists(path)
