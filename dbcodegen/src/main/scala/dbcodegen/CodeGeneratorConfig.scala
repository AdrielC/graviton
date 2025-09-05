package dbcodegen

import java.io.File
import java.nio.file.Path
import java.sql.SQLType
import scala.util.chaining.given

final case class CodeGeneratorConfig private[dbcodegen] (
  templateFiles: Seq[File],
  outDir: Path,
  typeMapping: (SQLType, Option[String]) => Option[String],
  schemaTableFilter: (String, String) => Boolean,
  scalafmt: Boolean,
  _scalaVersion: String,
  schemaCrawlerOptions: CodeGenerator.SchemaCrawlerOptions,
  mode: CodeGenerator.Mode,
)

object CodeGeneratorConfig {

  def apply(
    templateFiles: Seq[File],
    outDir: Path,
    typeMapping: (SQLType, Option[String]) => Option[String],
    schemaTableFilter: (String, String) => Boolean,
    scalafmt: Boolean,
    scalaVersion: String,
    schemaCrawlerOptions: CodeGenerator.SchemaCrawlerOptions,
    mode: CodeGenerator.Mode,
  ): CodeGeneratorConfig = new CodeGeneratorConfig(
    templateFiles, 
    Path.of(outDir.toString.replaceAll("/scala/", s"/scala-${scalaVersion.split("\\/|\\.").take(1).mkString(".")}/")), 
    typeMapping, 
    schemaTableFilter, 
    scalafmt, 
    scalaVersion, 
    schemaCrawlerOptions, 
    mode
  )

  val defaultScalaVersion = "3.7.2".tap(v => println(s"defaultScalaVersion: $v"))

  val default: CodeGeneratorConfig = CodeGeneratorConfig(
    templateFiles = Seq.empty,
      outDir = Path.of(s"modules/pg/src/main/scala/graviton/pg/generated"),
    typeMapping = (_: SQLType, guess: Option[String]) => guess.orElse(Some("java.lang.Object")),
    schemaTableFilter = (_: String, _: String) => true,
    scalafmt = true,
    scalaVersion = defaultScalaVersion,
    schemaCrawlerOptions = CodeGenerator.SchemaCrawlerOptions.default,
    mode = CodeGenerator.Mode.Production,
  )
}


