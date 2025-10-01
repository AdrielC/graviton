package dbcodegen

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import java.nio.file.Path
import java.sql.Connection
import scala.collection.mutable.ArrayBuffer
import scala.util.Using

object UpdateSnapshot:
  def main(args: Array[String]): Unit =
    val base = Path.of("..")
    val pg = EmbeddedPostgres.builder().setPort(0).start()
    try
      val connection = pg.getPostgresDatabase.getConnection
      try executeSqlFile(connection, base.resolve("modules/pg/ddl.sql"))
      finally connection.close()

      val config = CodeGeneratorConfig.default.copy(
        outDir = base.resolve("modules/pg/src/main/scala/graviton/pg/generated"),
        templateFiles = Seq.empty,
      )

      val _ = CodeGenerator.generate(
        jdbcUrl = pg.getJdbcUrl("postgres", "postgres"),
        username = Some("postgres"),
        password = Some("postgres"),
        config = config,
      )
    finally
      pg.close()

  private def executeSqlFile(connection: Connection, path: Path): Unit =
    val content = scala.io.Source.fromFile(path.toFile)(using scala.io.Codec.UTF8)
    try
      val sqlText = content.mkString
      splitStatements(sqlText).foreach { stmt =>
        Using.resource(connection.createStatement()) { statement =>
          statement.execute(stmt)
        }
      }
    finally
      content.close()

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
        val end = sql.indexOf("*/", idx + 2)
        val stop = if end == -1 then sql.length else end + 2
        current.append(sql.substring(idx, stop))
        idx = stop
      else
        val ch = sql.charAt(idx)
        ch match
          case '\'' =>
            inSingle = true
            current.append(ch)
            idx += 1
          case '"' =>
            inDouble = true
            current.append(ch)
            idx += 1
          case '$' =>
            val tag = extractDollarTag(sql, idx)
            if tag.nonEmpty then
              dollarTag = Some(tag)
              current.append(tag)
              idx += tag.length
            else
              current.append(ch)
              idx += 1
          case ';' =>
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
      } do end += 1
    if end < sql.length && sql.charAt(end) == '$' then sql.substring(start, end + 1)
    else ""
