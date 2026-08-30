package graviton.server

import graviton.backend.pg.{
  PgBlobManifestRepo,
  PgCatalog,
  PgKeyValueStore,
  PgMaintenanceCoordinator,
  PgMutableObjectStore,
  PgReplicaIndex,
  PgResumableUploadRepository,
}
import graviton.core.keys.BinaryKey
import graviton.core.locator.BlobLocator
import graviton.core.manifest.ManifestEntry
import graviton.core.ranges.Span
import graviton.core.types.{BlobOffset, FileSize, LocatorBucket, LocatorPath, LocatorScheme, RepositoryNamespace}
import graviton.runtime.config.MaintenanceConfig
import graviton.runtime.catalog.{CatalogError, CatalogName}
import graviton.runtime.model.BlobWritePlan
import graviton.runtime.kv.{KvKey, KvValue}
import graviton.runtime.stores.{BlobManifestRepo, BlobStore, CasBlobStore, FsBlockStore}
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
          ds  <- ZIO.attemptBlocking(pg.getPostgresDatabase)
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
    Path.of("modules/pg/ddl.sql")

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
