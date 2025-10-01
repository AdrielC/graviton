package dbcodegen

import java.nio.file.{Path, Files}
import java.io.File
import java.util.Locale
import scala.collection.immutable.Seq
import org.slf4j.LoggerFactory
import schemacrawler.tools.utility.SchemaCrawlerUtility
import schemacrawler.schemacrawler.SchemaCrawlerOptionsBuilder
import scala.jdk.CollectionConverters.given

extension (file: File)
  def toOption: Option[File] = Option(file)

object CodeGenerator {

  private lazy val log = LoggerFactory.getLogger(getClass())

  enum Mode(override val toString: String):
    case Production extends Mode("production")
    case Development extends Mode("development")
  end Mode
  
  object Mode:
    given Conversion[Mode, String] = _.toString
    def fromString(s: String): Mode = s match
      case "production" => Production
      case "development" => Development
      case _ => throw new IllegalArgumentException(s"Invalid mode: $s")
  end Mode

  case class SchemaCrawlerOptions(
    quoteIdentifiers: Boolean = true,
    excludeSchemaPattern: String = "pg_catalog",
    includeTables: Boolean = true,
    includeViews: Boolean = true,
    includeSequences: Boolean = true,
    includeSchemas: Boolean = true,
    includePackages: Boolean = true,
    includeConstraints: Boolean = true,
    includeCheckConstraints: Boolean = true,
    includeTableConstraints: Boolean = true,
  ):
    def toConfig: schemacrawler.tools.options.Config = {
      val map = 
        (productElementNames zip productIterator)
        .filter: 
          case (name, value) => value != null && value != ""
        .toMap.asJava
      schemacrawler.tools.options.Config(map)
    }

  object SchemaCrawlerOptions:
    val default = SchemaCrawlerOptions()
  end SchemaCrawlerOptions

  def generate(
    jdbcUrl: String,
    username: Option[String],
    password: Option[String],
    config: CodeGeneratorConfig,
  ): Seq[Path] = {
    
    println("🚀 Starting database schema generation...")
    log.debug(s"JDBC URL: $jdbcUrl, Username: $username")

    // Create database connection
    val ds = DbConnectionSource(jdbcUrl, username, password)

    // Set up schema crawler
    val crawlOpts = SchemaCrawlerOptionsBuilder.newSchemaCrawlerOptions()
    val retrOpts  = SchemaCrawlerUtility.matchSchemaRetrievalOptions(ds)
    val catalog   = SchemaCrawlerUtility.getCatalog(ds, retrOpts, crawlOpts, config.schemaCrawlerOptions.toConfig)

    // Group tables by schema
    val schemas = catalog.getSchemas().asScala.toSeq
    val tablesPerSchema = schemas.map { schema =>
      val tables = catalog.getTables(schema).asScala.toSeq
      schema -> tables
    }.toMap

    println("ℹ️  Note: Using direct code generation instead of template processing due to Scalate/Scala 3.7.2 incompatibility")

    // Generate code for each schema (one file per schema)
    val results = for {
      (schema, tables) <- tablesPerSchema.toSeq
      if tables.nonEmpty
      dataSchema = SchemaConverter.toDataSchema(schema, ds, tables, config)
    } yield {
      println(s"📝 Generating code for schema '${schema.getName}'")
      
      // Use simple code generation instead of templates
      val output = generateScalaCode(dataSchema)
      
      val outputPath = File(config.outDir.toString(), s"${dataSchema.name}.scala").toPath
      Files.createDirectories(outputPath.getParent)
      Files.write(outputPath, output.getBytes)
      
      println(s"✅ Generated: $outputPath")
      outputPath
    }

    println(s"🎉 Schema generation completed. Generated ${results.size} files.")
    
    // Log constraints for inspection
    inspectConstraints(tablesPerSchema)
    
    results
  }

  private def inspectConstraints(tablesPerSchema: Map[schemacrawler.schema.Schema, Seq[schemacrawler.schema.Table]]): Unit = {
    println("\n🔍 === DATABASE CONSTRAINTS INSPECTION ===")
    
    tablesPerSchema.foreach { case (schema, tables) =>
      if (tables.nonEmpty) {
        println(s"📊 Schema: ${schema.getName}")
        
        tables.foreach { table =>
          println(s"  📋 Table: ${table.getName}")
          
          // Primary Keys
          val primaryKeys = table.getPrimaryKey
          if (primaryKeys != null) {
            println(s"    🔑 Primary Key: ${primaryKeys.getName} (${primaryKeys.getConstrainedColumns.asScala.map(_.getName).mkString(", ")})")
          }
          
          // Foreign Keys
          table.getForeignKeys.asScala.foreach { fk =>
            println(s"    🔗 Foreign Key: ${fk.getName} (${fk.getColumnReferences.asScala.map(ref => s"${ref.getForeignKeyColumn.getName} -> ${ref.getPrimaryKeyColumn.getParent.getName}.${ref.getPrimaryKeyColumn.getName}").mkString(", ")})")
          }
          
          // Check Constraints
          table.getTableConstraints.asScala.foreach { constraint =>
            println(s"    ✅ Check Constraint: ${constraint.getName} - ${constraint.getDefinition}")
          }
          
          // Column constraints (NOT NULL, etc.)
          table.getColumns.asScala.foreach { column =>
            val constraints = Seq(
              if (!column.isNullable) Some("NOT NULL") else None,
              if (column.isAutoIncremented) Some("AUTO_INCREMENT") else None,
              if (column.isGenerated) Some("GENERATED") else None,
              if (column.hasDefaultValue) Some(s"DEFAULT ${column.getDefaultValue}") else None
            ).flatten
            
            if (constraints.nonEmpty) {
              println(s"    📝 Column ${column.getName}: ${constraints.mkString(", ")}")
            }
          }
        }
      }
    }
    println("=== END CONSTRAINTS INSPECTION ===\n")
  }

  private def generateScalaCode(schema: DataSchema): String = {
    val sb = new StringBuilder
    sb.append("package graviton.pg.generated\n\n")
    sb.append("import com.augustnagro.magnum.*\n")
    sb.append("import graviton.db.{*, given}\n")
    sb.append("import zio.Chunk\n")
    sb.append("import zio.json.ast.Json\n")
    sb.append("import zio.schema.{DeriveSchema, Schema}\n\n")

    schema.tables.foreach { table =>
      sb.append("@Table(PostgresDbType)\n")
      sb.append(s"final case class ${table.scalaName}(\n")
      val columns = table.columns
      columns.zipWithIndex.foreach { case (column, idx) =>
        if (column.db.isPartOfPrimaryKey) sb.append("  @Id\n")
        sb.append(s"  @SqlName(\"${column.name}\")\n")
        val tpe = renderColumnType(column)
        val suffix = if (idx == columns.size - 1) "" else ","
        sb.append(s"  ${column.scalaName}: $tpe$suffix\n")
      }
      sb.append(") derives DbCodec\n\n")

      sb.append(s"object ${table.scalaName}:\n")
      val primaryKeyColumns = columns.filter(_.db.isPartOfPrimaryKey)

      if primaryKeyColumns.isEmpty then
        sb.append("  type Id = Unit\n\n")
      else
        sb.append("  final case class Id(\n")
        primaryKeyColumns.zipWithIndex.foreach { case (column, idx) =>
          val suffix = if idx == primaryKeyColumns.size - 1 then "" else ","
          sb.append(s"    ${column.scalaName}: ${renderColumnType(column, forceRequired = true)}$suffix\n")
        }
        sb.append("  ) derives DbCodec\n\n")

        if hasAutoGeneratedPrimaryKey(primaryKeyColumns) then
          sb.append("  type OptionalId = Option[Id]\n\n")

      if (!table.isView) {
        val creatorColumns = columns.filter { column =>
          val dbColumn = column.db
          !dbColumn.isGenerated && !dbColumn.isAutoIncremented && !dbColumn.hasDefaultValue
        }

        if (creatorColumns.nonEmpty) {
          sb.append(s"  final case class Creator(\n")
          creatorColumns.zipWithIndex.foreach { case (column, idx) =>
            sb.append(s"    ${column.scalaName}: ${renderColumnType(column)}${if (idx == creatorColumns.size - 1) "" else ","}\n")
          }
          sb.append("  ) derives DbCodec\n\n")
        } else {
          sb.append("  type Creator = Unit\n\n")
        }
      }

      if (table.isView)
        sb.append(s"  val repo = ImmutableRepo[${table.scalaName}, ${table.scalaName}.Id]\n\n")
      else
        sb.append(s"  val repo = Repo[${table.scalaName}.Creator, ${table.scalaName}, ${table.scalaName}.Id]\n\n")
    }

    sb.append(renderZioSchemas(schema))

    sb.toString
  }

  private def hasAutoGeneratedPrimaryKey(primaryKeyColumns: Seq[DataColumn]): Boolean =
    primaryKeyColumns.exists { column =>
      val dbColumn = column.db
      dbColumn.isGenerated || dbColumn.isAutoIncremented || dbColumn.hasDefaultValue
    }

  private def renderColumnType(column: DataColumn, forceRequired: Boolean = false): String = {
    val base = baseColumnType(column)
    val isOptional = !forceRequired && column.db.isNullable && !column.db.isPartOfPrimaryKey
    if (isOptional) s"Option[$base]" else base
  }

  private def baseColumnType(column: DataColumn): String = {
    val domainOverride = column.pgType.flatMap(info => domainTypeMapping.get(info.typname.toLowerCase(Locale.ROOT)))

    val underlying =
      if (column.scalaType.startsWith("Option["))
        column.scalaType.stripPrefix("Option[").stripSuffix("]")
      else column.scalaType

    val byDomain = domainOverride.getOrElse {
      val dbTypeName = Option(column.db.getColumnDataType.getName).map(_.toLowerCase(Locale.ROOT)).getOrElse("")
      dbSpecificTypes.getOrElse(dbTypeName, underlying)
    }

    refineNumericTypes(byDomain, column)
  }

  private val domainTypeMapping: Map[String, String] = Map(
    "store_key"         -> "StoreKey",
    "hash_bytes"        -> "HashBytes",
    "small_bytes"       -> "SmallBytes",
    "store_status_t"    -> "StoreStatus",
    "replica_status_t"  -> "ReplicaStatus",
  )

  private val dbSpecificTypes: Map[String, String] = Map(
    "bytea"        -> "Chunk[Byte]",
    "json"         -> "Json",
    "jsonb"        -> "Json",
    "uuid"         -> "java.util.UUID",
    "timestamptz"  -> "java.time.OffsetDateTime",
    "timestamp"    -> "java.time.OffsetDateTime",
    "int8range"    -> "DbRange[Long]",
  )

  private def refineNumericTypes(baseType: String, column: DataColumn): String = {
    val lowered = column.name.toLowerCase(Locale.ROOT)
    baseType match {
      case "Long" if lowered.contains("size") || lowered.contains("bytes") => "PosLong"
      case "Long" if lowered.contains("offset")                             => "NonNegLong"
      case "Long" if lowered.contains("version")                            => "NonNegLong"
      case other                                                             => other
    }
  }

  private def renderZioSchemas(schema: DataSchema): String = {
    val sb = new StringBuilder
    sb.append(s"// ZIO Schema definitions for ${schema.name}\n")
    sb.append("object Schemas {\n")

    schema.tables.foreach { table =>
      val tableGiven = schemaGivenName(table.scalaName)
      sb.append(s"  given $tableGiven: Schema[${table.scalaName}] = DeriveSchema.gen[${table.scalaName}]\n")

      val primaryKeyColumns = table.columns.filter(_.db.isPartOfPrimaryKey)
      if primaryKeyColumns.nonEmpty then
        val idGiven = schemaGivenName(s"${table.scalaName}.Id")
        sb.append(s"  given $idGiven: Schema[${table.scalaName}.Id] = DeriveSchema.gen[${table.scalaName}.Id]\n")

      if (!table.isView) {
        val creatorGiven = schemaGivenName(s"${table.scalaName}.Creator")
        sb.append(s"  given $creatorGiven: Schema[${table.scalaName}.Creator] = DeriveSchema.gen[${table.scalaName}.Creator]\n")
      }
    }

    sb.append("}\n")
    sb.toString
  }

  private def schemaGivenName(typeName: String): String = {
    val pascal = typeName
      .split("\\.")
      .filter(_.nonEmpty)
      .map(_.capitalize)
      .mkString

    val camel =
      if pascal.isEmpty then "schema"
      else s"${pascal.head.toLower}${pascal.tail}"

    s"${camel}Schema"
  }
}