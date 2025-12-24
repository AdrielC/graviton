package dbcodegen

import org.flywaydb.core.api.FlywayException
import org.flywaydb.core.api.configuration.ClassicConfiguration
import org.flywaydb.core.internal.database.DatabaseTypeRegister
import org.flywaydb.core.internal.parser.ParsingContext
import org.flywaydb.core.internal.resource.StringResource

import java.io.File
import java.sql.Connection
import scala.collection.mutable.ArrayBuffer
import scala.io.Source
import scala.jdk.CollectionConverters._
import scala.util.Using

object SqlExecutor {
    
  def executeSqlFile(connection: Connection, file: File): Unit =
    Using.resource(Source.fromFile(file)) { fileSource =>
      executeSql(connection, fileSource.mkString)
    }

  def executeSql(connection: Connection, sql: String): Unit =
    try
      val databaseType = DatabaseTypeRegister.getDatabaseTypeForConnection(connection)
      val factory      = databaseType.createSqlScriptFactory(new ClassicConfiguration(), new ParsingContext())
      val resource     = new StringResource(sql)
      val sqlScript    = factory.createSqlScript(resource, false, null)
      Using.resource(connection.createStatement()) { statement =>
        sqlScript.getSqlStatements.asScala.foreach(sqlStatement => statement.execute(sqlStatement.getSql))
      }
    catch
      case _: FlywayException =>
        Using.resource(connection.createStatement()) { statement =>
          splitStatements(sql).foreach(statement.execute)
        }

  /** Fallback SQL splitter that handles:
    * - `--` line comments
    * - `/* ... */` block comments
    * - single and double quoted strings
    * - Postgres dollar-quoted blocks (e.g. `DO $$ ... $$;`)
    */
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
}