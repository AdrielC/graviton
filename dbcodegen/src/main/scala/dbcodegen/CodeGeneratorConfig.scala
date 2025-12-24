package dbcodegen

import java.io.File
import java.nio.file.Path
import java.sql.{SQLType, Types}
import scala.util.matching.Regex

enum OutputLayout:
  /** Write one file per schema in `outDir/<schema>/schema.scala` */
  case PerSchemaDirectory
  /** Write one file per schema in `outDir/<schema>.scala` */
  case FlatFiles

object OutputLayout:
  def fromString(value: String): Option[OutputLayout] =
    Option(value).map(_.trim.toLowerCase).collect {
      case "per-schema" | "per-schema-directory" | "per-schema-dir" | "per_schema" => OutputLayout.PerSchemaDirectory
      case "flat" | "flat-files" | "flat_files"                                    => OutputLayout.FlatFiles
    }

final case class CodeGeneratorConfig private[dbcodegen] (
  templateFiles: Seq[File],
  outDir: Path,
  /** Base package for generated code (schema is appended as a subpackage by default). */
  basePackage: String,
  /** How generated files are laid out on disk. */
  outputLayout: OutputLayout,
  typeMapping: (SQLType, Option[String]) => Option[String],
  /** If non-empty, only these schemas are included. */
  includeSchemas: Set[String],
  /** Always excluded schemas (case-sensitive, as reported by SchemaCrawler). */
  excludeSchemas: Set[String],
  /** Optional include table name regex (applied after schema filtering). */
  includeTablePattern: Option[Regex],
  /** Optional exclude table name regex (applied after schema filtering). */
  excludeTablePattern: Option[Regex],
  scalafmt: Boolean,
  schemaCrawlerOptions: CodeGenerator.SchemaCrawlerOptions,
  mode: CodeGenerator.Mode,
  /** If true, prints constraint inspection output. */
  inspectConstraints: Boolean,
  /** If true, generates code but does not write files. */
  dryRun: Boolean,
)

object CodeGeneratorConfig {

  def apply(
    templateFiles: Seq[File],
    outDir: Path,
    basePackage: String,
    outputLayout: OutputLayout,
    typeMapping: (SQLType, Option[String]) => Option[String],
    includeSchemas: Set[String],
    excludeSchemas: Set[String],
    includeTablePattern: Option[Regex],
    excludeTablePattern: Option[Regex],
    scalafmt: Boolean,
    schemaCrawlerOptions: CodeGenerator.SchemaCrawlerOptions,
    mode: CodeGenerator.Mode,
    inspectConstraints: Boolean,
    dryRun: Boolean,
  ): CodeGeneratorConfig = new CodeGeneratorConfig(
    templateFiles,
    outDir,
    basePackage,
    outputLayout,
    typeMapping,
    includeSchemas,
    excludeSchemas,
    includeTablePattern,
    excludeTablePattern,
    scalafmt,
    schemaCrawlerOptions,
    mode,
    inspectConstraints,
    dryRun,
  )

  private val defaultExcludeSchemas: Set[String] =
    Set("pg_catalog", "information_schema")

  val default: CodeGeneratorConfig = CodeGeneratorConfig(
    templateFiles = Seq.empty,
    outDir = Path.of("modules/pg/src/main/scala/graviton/pg/generated"),
    basePackage = "graviton.pg.generated",
    outputLayout = OutputLayout.PerSchemaDirectory,
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
    includeSchemas = Set.empty,
    excludeSchemas = defaultExcludeSchemas,
    includeTablePattern = None,
    excludeTablePattern = Some(".*_p\\d+$".r),
    scalafmt = true,
    schemaCrawlerOptions = CodeGenerator.SchemaCrawlerOptions.default,
    mode = CodeGenerator.Mode.Production,
    inspectConstraints = false,
    dryRun = false,
  )

  extension (config: CodeGeneratorConfig)
    private def normalizedSchemaSet(values: Set[String]): Set[String] =
      values.iterator.map(_.trim).filter(_.nonEmpty).toSet

    def isSchemaIncluded(schema: String): Boolean = {
      val s = Option(schema).map(_.trim).filter(_.nonEmpty).getOrElse("")
      if (s.isEmpty) false
      else if (normalizedSchemaSet(config.excludeSchemas).contains(s)) false
      else {
        val include = normalizedSchemaSet(config.includeSchemas)
        include.isEmpty || include.contains(s)
      }
    }

    def isTableIncluded(schema: String, table: String): Boolean = {
      val t = Option(table).map(_.trim).filter(_.nonEmpty).getOrElse("")
      if (!config.isSchemaIncluded(schema)) false
      else if (t.isEmpty) false
      else {
        val includedByInclude = config.includeTablePattern.forall(_.pattern.matcher(t).matches())
        val excludedByExclude = config.excludeTablePattern.exists(_.pattern.matcher(t).matches())
        includedByInclude && !excludedByExclude
      }
    }
}


