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

    println("ℹ️  Note: Using direct code generation instead of template processing due to Scalate/Scala 3.7.3 incompatibility")

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
    sb.append("// Code generated by dbcodegen. DO NOT EDIT.\n")
    sb.append(s"// Schema: ${schema.name}\n\n")
    sb.append("package graviton.pg.generated\n\n")
    sb.append("import com.augustnagro.magnum.*\n")
    sb.append("import graviton.db.{*, given}\n")
    sb.append("import zio.Chunk\n")
    sb.append("import zio.json.ast.Json\n")
    // ZIO Schema imports disabled; schema emission is turned off to avoid derivation issues
    // sb.append("import zio.schema.{DeriveSchema, Schema}\n\n")

    schema.tables.foreach { table =>
      // Build FK → referenced Id type overrides for this table
      val fkIdTypeByLocalCol: Map[String, String] = {
        val t       = table.db
        val pkByRef = Option(t.getForeignKeys).toSeq.flatMap(_.asScala).flatMap { fk =>
          fk.getColumnReferences.asScala.flatMap { ref =>
            val localCol  = Option(ref.getForeignKeyColumn).map(_.getName)
            val pkColOpt  = Option(ref.getPrimaryKeyColumn)
            val targetTbl = pkColOpt.map(_.getParent)
            (localCol, pkColOpt, targetTbl) match {
              case (Some(local), Some(pkCol), Some(target: schemacrawler.schema.Table)) =>
                val targetPkCols = Option(target.getPrimaryKey)
                  .map(_.getConstrainedColumns.asScala.map(_.getName).toSet)
                  .getOrElse(Set.empty)
                // Only map when target has a single-column PK and this FK points to it
                if (targetPkCols.size == 1 && targetPkCols.contains(pkCol.getName)) {
                  val targetType = dbcodegen.NameFormat.sanitizeScalaName(dbcodegen.NameFormat.toPascalCase(target.getName))
                  Some(local -> s"${targetType}.Id")
                } else None
              case _ => None
            }
          }
        }
        pkByRef.toMap
      }
      sb.append("@Table(PostgresDbType)\n")
      sb.append(s"final case class ${table.scalaName}(\n")
      val columns = table.columns
      columns.zipWithIndex.foreach { case (column, idx) =>
        if (column.db.isPartOfPrimaryKey) sb.append("  @Id\n")
        sb.append(s"  @SqlName(\"${column.name}\")\n")
        val tpe = fkIdTypeByLocalCol
          .get(column.name)
          .map { base => if (!column.db.isPartOfPrimaryKey && column.db.isNullable) s"Option[$base]" else base }
          .getOrElse(renderColumnType(column))
        val suffix = if (idx == columns.size - 1) "" else ","
        sb.append(s"  ${column.scalaName}: $tpe$suffix\n")
      }
      sb.append(") derives DbCodec\n\n")

      sb.append(s"object ${table.scalaName}:\n")
      val primaryKeyColumns = columns.filter(_.db.isPartOfPrimaryKey)

      if (primaryKeyColumns.isEmpty) then
        sb.append("  type Id = Null\n\n")
      else if (primaryKeyColumns.size == 1) then
        val col       = primaryKeyColumns.head
        val baseType  = renderColumnType(col, forceRequired = true)
        val paramName = col.scalaName
        sb.append(s"  opaque type Id <: $baseType = $baseType\n")
        sb.append("  object Id:\n")
        if (ironTypeNames.contains(baseType)) then
          sb.append(s"    def apply($paramName: $baseType): Id = $paramName\n")
          sb.append(s"    def from(value: $baseType): Id = value\n")
          
        else
          sb.append(s"    def make($paramName: $baseType): Either[String, Id] = Right($paramName)\n")
          sb.append(s"    def from(value: $baseType): Id = value\n")
          
        sb.append(s"    given DbCodec[Id] = scala.compiletime.summonInline[DbCodec[$baseType]].biMap(value => from(value), id => value(id))\n\n")
        sb.append("  export Id.given\n")
        sb.append("  extension (id: Id)\n")
        sb.append(s"    def value: $baseType = id\n\n")
      else
        val tupleType = renderNamedTuple(primaryKeyColumns)
        val tupleCtor = renderTupleCtor(primaryKeyColumns)
        sb.append(s"  opaque type Id <: $tupleType = $tupleType\n")
        sb.append("  object Id:\n")
        if (primaryKeyColumns.forall(c => ironTypeNames.contains(renderColumnType(c, forceRequired = true)))) then
          sb.append(s"    def apply($tupleCtor): Id = ${renderNamedTupleLiteralFromParams(primaryKeyColumns)}\n")
          sb.append(s"    def from(value: $tupleType): Id = value\n")
        else
          sb.append(s"    def make($tupleCtor): Either[String, Id] = Right(${renderNamedTupleLiteralFromParams(primaryKeyColumns)})\n")
          sb.append(s"    def from(value: $tupleType): Id = value\n")
        sb.append("  export Id.given\n")
        sb.append("  extension (id: Id)\n")
        sb.append(s"    def value: $tupleType = id\n\n")
        sb.append(s"  def apply($tupleCtor): $tupleType = ${renderNamedTupleLiteralFromParams(primaryKeyColumns)}\n\n")
        val codecSource = renderIdCodecSource(table.scalaName, primaryKeyColumns)
        sb.append(s"    given DbCodec[Id] = $codecSource\n\n")
        sb.append("\n")

      if (!table.isView) {
        val autoPrimaryKey =
          primaryKeyColumns.nonEmpty && primaryKeyColumns.forall { column =>
            val dbColumn = column.db
            dbColumn.isGenerated || dbColumn.isAutoIncremented || dbColumn.hasDefaultValue
          }
        val creatorColumns = columns.filterNot { column =>
          val dbColumn = column.db
          val skipAutoPrimaryKey = autoPrimaryKey && dbColumn.isPartOfPrimaryKey
          val skipGenerated      = dbColumn.isGenerated && !dbColumn.hasDefaultValue
          skipAutoPrimaryKey || skipGenerated
        }

        val creatorFields =
          (if autoPrimaryKey then Seq(s"id: Option[${table.scalaName}.Id] = None") else Seq.empty) ++
            creatorColumns.map(renderCreatorField)

        if (creatorFields.nonEmpty) {
          sb.append(s"  final case class Creator(\n")
          creatorFields.zipWithIndex.foreach { case (line, idx) =>
            val suffix = if idx == creatorFields.size - 1 then "" else ","
            sb.append(s"    $line$suffix\n")
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

    // ZIO Schema emission disabled by default to avoid derivation issues for external/primitive types
    // sb.append(renderZioSchemas(schema))

    sb.toString
  }

  private def renderColumnType(column: DataColumn, forceRequired: Boolean = false): String = {
    val base = baseColumnType(column)
    val isOptional = !forceRequired && column.db.isNullable && !column.db.isPartOfPrimaryKey
    if (isOptional) s"Option[$base]" else base
  }

  private def renderCreatorField(column: DataColumn): String = {
    val baseType            = baseColumnType(column)
    val dbColumn            = column.db
    val optionalFromDefault = dbColumn.hasDefaultValue || dbColumn.isGenerated || dbColumn.isAutoIncremented
    val optionalFromNull    = dbColumn.isNullable && !dbColumn.isPartOfPrimaryKey
    val isOptional          = optionalFromDefault || optionalFromNull
    val renderedType        = if (isOptional) s"Option[$baseType]" else baseType
    val defaultValue        = if (isOptional) " = None" else ""
    s"${column.scalaName}: $renderedType$defaultValue"
  }

  private def renderNamedTuple(columns: Seq[DataColumn]): String = {
    columns.toList match
      case Nil => "Null"
      case single :: Nil =>
        s"(${single.scalaName}: ${renderColumnType(single, forceRequired = true)})"
      case many =>
        many
          .map { column =>
            s"    ${column.scalaName}: ${renderColumnType(column, forceRequired = true)}"
          }
          .mkString("(\n", ",\n", "\n  )")
  }

  private def renderTupleType(columns: Seq[DataColumn]): String = {
    columns.toList match
      case Nil => "Null"
      case single :: Nil => s"Tuple1[${renderColumnType(single, forceRequired = true)}]"
      case many => many.map(column => renderColumnType(column, forceRequired = true)).mkString("(", ", ", ")")
  }

  private def renderTupleCtor(columns: Seq[DataColumn]): String =
    columns
      .map(column => s"${column.scalaName}: ${renderColumnType(column, forceRequired = true)}")
      .mkString(", ")

  private def baseColumnType(column: DataColumn): String = {
    val domainOverride =
      column.pgType
        .flatMap(info => domainTypeMapping.get(info.typname.toLowerCase(Locale.ROOT)))
        .orElse(
          Option(column.db.getColumnDataType.getName)
            .map(_.toLowerCase(Locale.ROOT))
            .flatMap(domainTypeMapping.get)
        )

    val underlying =
      if (column.scalaType.startsWith("Option["))
        column.scalaType.stripPrefix("Option[").stripSuffix("]")
      else column.scalaType

    val byDomain = domainOverride.getOrElse {
      val dbTypeName = Option(column.db.getColumnDataType.getName).map(_.toLowerCase(Locale.ROOT)).getOrElse("")
      dbSpecificTypes.getOrElse(dbTypeName, underlying)
    }

    val normalized = byDomain match
      case "java.sql.Blob" | "Blob" => "Chunk[Byte]"
      case other                     => other

    refineNumericTypes(normalized, column)
  }

  private val ironTypeNames: Set[String] = Set(
    "StoreKey",
    "HashBytes",
    "SmallBytes",
    "PosLong",
    "NonNegLong",
  )

  private val domainTypeMapping: Map[String, String] = Map(
    "store_key"         -> "StoreKey",
    "hash_bytes"        -> "HashBytes",
    "small_bytes"       -> "SmallBytes",
    "store_status_t"    -> "StoreStatus",
    "replica_status_t"  -> "ReplicaStatus",
  )

  private val dbSpecificTypes: Map[String, String] = Map(
    "bytea"        -> "Chunk[Byte]",
    "store_key"    -> "StoreKey",
    "hash_bytes"   -> "HashBytes",
    "small_bytes"  -> "SmallBytes",
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

  private def renderNamedTupleLiteralFromParams(columns: Seq[DataColumn]): String =
    renderNamedTupleLiteral(columns, _.scalaName)

  private def renderNamedTupleLiteral(columns: Seq[DataColumn], valueFor: DataColumn => String): String =
    columns.toList match
      case Nil => "EmptyTuple"
      case single :: Nil => s"(${single.scalaName} = ${valueFor(single)})"
      case many =>
        many
          .map(column => s"${column.scalaName} = ${valueFor(column)}")
          .mkString("(", ", ", ")")

  private def renderIdCodecSource(tableName: String, columns: Seq[DataColumn]): String = {
    val codecType = renderCodecPlainType(columns)
    val toId      = renderCodecToId(tableName, columns, "value")
    val fromId    = renderCodecFromId(tableName, columns, "id")
    s"scala.compiletime.summonInline[DbCodec[$codecType]].biMap(value => $toId, id => $fromId)"
  }

  private def renderCodecPlainType(columns: Seq[DataColumn]): String =
    columns.toList match
      case Nil => "Null"
      case single :: Nil => renderColumnType(single, forceRequired = true)
      case _ => renderTupleType(columns)

  private def renderCodecToId(tableName: String, columns: Seq[DataColumn], valueExpr: String): String =
    columns.toList match
      case Nil => s"$tableName.Id(null)"
      case single :: Nil =>
        val literal = renderNamedTupleLiteral(columns, _ => valueExpr)
        s"$tableName.Id($literal)"
      case _ =>
        val literal = renderNamedTupleLiteralFromTuple(columns, valueExpr)
        s"$tableName.Id($literal)"

  private def renderCodecFromId(tableName: String, columns: Seq[DataColumn], idExpr: String): String =
    columns.toList match
      case Nil => "EmptyTuple"
      case single :: Nil => s"$tableName.Id($idExpr).${single.scalaName}"
      case _ =>
        val namedExpr = s"($idExpr.value)"
        renderPlainTupleFromNamed(namedExpr, columns)

  // private def renderIdSchemaSource(tableName: String, columns: Seq[DataColumn]): String = {
  //   val schemaType = renderCodecPlainType(columns)
  //   val toId       = renderCodecToId(tableName, columns, "value")
  //   val fromId     = renderCodecFromId(tableName, columns, "id")
  //   s"scala.compiletime.summonInline[Schema[$schemaType]].transform(value => $toId, id => $fromId)"
  // }

  private def renderNamedTupleLiteralFromTuple(columns: Seq[DataColumn], tupleExpr: String): String =
    columns.toList match
      case Nil => "Null"
      case single :: Nil => s"(${single.scalaName} = $tupleExpr)"
      case many =>
        many
          .zipWithIndex
          .map { case (column, idx) => s"${column.scalaName} = $tupleExpr._${idx + 1}" }
          .mkString("(", ", ", ")")

  private def renderPlainTupleFromNamed(namedExpr: String, columns: Seq[DataColumn]): String =
    columns.toList match
      case Nil => "Null"
      case single :: Nil => s"$namedExpr.${single.scalaName}"
      case many => many.map(column => s"$namedExpr.${column.scalaName}").mkString("(", ", ", ")")

  // private def renderIdComponentAccessor(tableName: String, column: DataColumn, idExpr: String): String =
  //   s"$tableName.Id.toTuple($idExpr).${column.scalaName}"

  // ZIO Schema emission disabled

  // private def schemaGivenName(typeName: String): String = {
  //   val pascal = typeName
  //     .split("\\.")
  //     .filter(_.nonEmpty)
  //     .map(_.capitalize)
  //     .mkString

  //   val camel =
  //     if pascal.isEmpty then "schema"
  //     else s"${pascal.head.toLower}${pascal.tail}"

  //   s"${camel}Schema"
  // }
}