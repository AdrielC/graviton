package dbcodegen

import java.io.File
import java.nio.file.Path
import java.sql.{SQLType, Types}

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

  val defaultScalaVersion = "3.7.3"

  val default: CodeGeneratorConfig = CodeGeneratorConfig(
    templateFiles = Seq.empty,
    outDir = Path.of("modules/pg/src/main/scala/graviton/pg/generated"),
    typeMapping = (sqlType: SQLType, guess: Option[String]) => {
      val vendor = Option(sqlType.getVendorTypeNumber).map(_.intValue()).getOrElse(Int.MinValue)
      vendor match
        case Types.BLOB | Types.BINARY | Types.VARBINARY | Types.LONGVARBINARY => Some("Chunk[Byte]")
        case _ =>
          guess
            .map {
              case "java.sql.Blob" => "Chunk[Byte]"
              case other            => other
            }
            .orElse(Some("java.lang.Object"))
    },
    schemaTableFilter = (schema: String, table: String) => {
      val s = Option(schema).getOrElse("")
      val t = Option(table).getOrElse("")
      val allowedSchema = Set("core", "graviton", "quasar").contains(s)
      val isPartitionChild = t.matches(".*_p\\d+$")
      allowedSchema && !isPartitionChild
    },
    scalafmt = true,
    scalaVersion = defaultScalaVersion,
    schemaCrawlerOptions = CodeGenerator.SchemaCrawlerOptions.default,
    mode = CodeGenerator.Mode.Production,
  )
}


