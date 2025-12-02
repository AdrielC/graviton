package dbcodegen

import org.flywaydb.core.api.configuration.ClassicConfiguration
import org.flywaydb.core.internal.database.DatabaseTypeRegister
import org.flywaydb.core.internal.parser.ParsingContext
import org.flywaydb.core.internal.resource.StringResource

import java.io.File
import java.sql.Connection
import scala.io.Source
import scala.jdk.CollectionConverters._

import zio.*

object SqlExecutor:
    
  def executeSqlFile(connection: Connection, file: File): ZIO[Scope, Throwable, Unit] =
    ZIO.fromAutoCloseable(ZIO.attempt(Source.fromFile(file)))
    .flatMap: fileSource =>
      ZIO.attempt(executeSql(connection, fileSource.mkString))
      .unit
    
  end executeSqlFile

  def executeSql(connection: Connection, sql: String): ZIO[Scope, Throwable, Unit] =
    val databaseType = DatabaseTypeRegister.getDatabaseTypeForConnection(connection)
    val factory      = databaseType.createSqlScriptFactory(ClassicConfiguration(), ParsingContext())
    val resource     = StringResource(sql)
    val sqlScript    = factory.createSqlScript(resource, false, null)
    
    ZIO.fromAutoCloseable:
      ZIO.attempt:
        connection.createStatement()
    .flatMap: statement =>
      ZIO.foreachDiscard(
        sqlScript.getSqlStatements.asScala.toSeq
      ): sqlStatement => 
        ZIO.attempt:
          statement.execute(sqlStatement.getSql)

  end executeSql