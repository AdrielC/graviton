package graviton.server

import graviton.backend.pg.PgBlobManifestRepo
import graviton.runtime.model.BlobWritePlan
import graviton.runtime.stores.{BlobStore, CasBlobStore, FsBlockStore}
import graviton.core.types.UploadChunkSize
import graviton.streams.Chunker
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import zio.*
import zio.stream.ZStream
import zio.test.*

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.sql.Connection
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

  private val blobStoreLayer: ZLayer[Any, Throwable, BlobStore] =
    embeddedPgLayer >>> ZLayer.scoped {
      for
        ds   <- ZIO.service[javax.sql.DataSource]
        root <- ZIO.attemptBlocking(Files.createTempDirectory("graviton-fs-blocks"))
        _    <- ZIO.addFinalizer(ZIO.attemptBlocking(deleteRecursive(root)).orDie)
        repo  = new PgBlobManifestRepo(ds)
        bs    = new FsBlockStore(root)
      yield new CasBlobStore(bs, repo)
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
          val data =
            Chunk.fromArray(("hello-embeddedpg-fs-" * 2000).getBytes(StandardCharsets.UTF_8))

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
