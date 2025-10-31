package dbcodegen

import java.nio.file.{Path, Files}
import java.io.File
import java.util.Locale
import scala.collection.immutable.Seq
import scala.collection.mutable.ListBuffer
import org.slf4j.LoggerFactory
import schemacrawler.tools.utility.SchemaCrawlerUtility
import schemacrawler.schemacrawler.SchemaCrawlerOptionsBuilder
import scala.jdk.CollectionConverters.given

import CheckConstraintParser.*

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
    val validationResults = schema.tables.map { table =>
      table.scalaName -> buildTableValidations(table)
    }.toMap

    val validationWarnings = validationResults.values.flatMap(_.warnings).distinct
    validationWarnings.foreach { warning => println(s"⚠️  $warning") }

    val sb = new StringBuilder
    sb.append("// Code generated by dbcodegen. DO NOT EDIT.\n")
    sb.append(s"// Schema: ${schema.name}\n\n")
    sb.append("package graviton.pg.generated\n\n")
    sb.append("import com.augustnagro.magnum.*\n")
    sb.append("import graviton.db.{*, given}\n")
    sb.append("import zio.Chunk\n")
    sb.append("import zio.json.ast.Json\n")
    sb.append("import zio.schema.{DeriveSchema, Schema}\n")
    sb.append("import zio.schema.validation.Validation\n\n")

    val enumsRendered = renderEnums(schema)
    if enumsRendered.nonEmpty then
      sb.append(enumsRendered)
      sb.append("\n")

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

      if (primaryKeyColumns.isEmpty) then
        sb.append("  type Id = Null\n\n")
      else
        val namedTuple  = renderNamedTuple(primaryKeyColumns)
        val tupleType   = renderTupleType(primaryKeyColumns)
        val tupleCtor   = renderTupleCtor(primaryKeyColumns)
        val idCodecName = "given_DbCodec_Id"

        sb.append(s"  opaque type Id <: Tuple = $tupleType\n")
        sb.append(s"  type Tupled = $namedTuple\n\n")

        sb.append("  object Id:\n")
        sb.append(s"    def fromTuple(tuple: Tupled): Id = tuple.asInstanceOf[Id]\n")
        sb.append(s"    def toTuple(id: Id): Tupled      = id.asInstanceOf[Tupled]\n")
        sb.append(s"    def apply($tupleCtor): Id        = fromTuple(${renderNamedTupleLiteralFromParams(primaryKeyColumns)})\n\n")

        val codecSource = renderIdCodecSource(table.scalaName, primaryKeyColumns)
        sb.append(s"  given $idCodecName: DbCodec[Id] = $codecSource\n\n")

        sb.append("  extension (id: Id)\n")
        primaryKeyColumns.foreach { column =>
          val accessor = column.scalaName
          val body     = renderIdComponentAccessor(table.scalaName, column, "id")
          sb.append(s"    def $accessor: ${renderColumnType(column, forceRequired = true)} = $body\n")
        }
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

    sb.append(renderZioSchemas(schema, validationResults))

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
      case Nil => "Unit"
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
      case Nil => "EmptyTuple"
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
      case Nil => "Unit"
      case single :: Nil => renderColumnType(single, forceRequired = true)
      case _ => renderTupleType(columns)

  private def renderCodecToId(tableName: String, columns: Seq[DataColumn], valueExpr: String): String =
    columns.toList match
      case Nil => s"$tableName.Id.fromTuple(EmptyTuple)"
      case single :: Nil =>
        val literal = renderNamedTupleLiteral(columns, _ => valueExpr)
        s"$tableName.Id.fromTuple($literal)"
      case _ =>
        val literal = renderNamedTupleLiteralFromTuple(columns, valueExpr)
        s"$tableName.Id.fromTuple($literal)"

  private def renderCodecFromId(tableName: String, columns: Seq[DataColumn], idExpr: String): String =
    columns.toList match
      case Nil => "EmptyTuple"
      case single :: Nil => s"$tableName.Id.toTuple($idExpr).${single.scalaName}"
      case _ =>
        val namedExpr = s"$tableName.Id.toTuple($idExpr)"
        renderPlainTupleFromNamed(namedExpr, columns)

  private def renderIdSchemaSource(tableName: String, columns: Seq[DataColumn]): String = {
    val schemaType = renderCodecPlainType(columns)
    val toId       = renderCodecToId(tableName, columns, "value")
    val fromId     = renderCodecFromId(tableName, columns, "id")
    s"scala.compiletime.summonInline[Schema[$schemaType]].transform(value => $toId, id => $fromId)"
  }

  private def renderNamedTupleLiteralFromTuple(columns: Seq[DataColumn], tupleExpr: String): String =
    columns.toList match
      case Nil => "EmptyTuple"
      case single :: Nil => s"(${single.scalaName} = $tupleExpr)"
      case many =>
        many
          .zipWithIndex
          .map { case (column, idx) => s"${column.scalaName} = $tupleExpr._${idx + 1}" }
          .mkString("(", ", ", ")")

  private def renderPlainTupleFromNamed(namedExpr: String, columns: Seq[DataColumn]): String =
    columns.toList match
      case Nil => "EmptyTuple"
      case single :: Nil => s"$namedExpr.${single.scalaName}"
      case many => many.map(column => s"$namedExpr.${column.scalaName}").mkString("(", ", ", ")")

  private def renderIdComponentAccessor(tableName: String, column: DataColumn, idExpr: String): String =
    s"$tableName.Id.toTuple($idExpr).${column.scalaName}"

  private def renderZioSchemas(schema: DataSchema, validationMap: Map[String, TableValidationRender]): String = {
    val sb = new StringBuilder
    sb.append(s"// ZIO Schema definitions for ${schema.name}\n")
    sb.append("object Schemas {\n")

    schema.tables.foreach { table =>
      val tableGiven = schemaGivenName(table.scalaName)

      val tableValidations = validationMap.getOrElse(table.scalaName, TableValidationRender(Seq.empty, Seq.empty))
      combineValidationExpressions(table.scalaName, tableValidations.expressions) match
        case Some(validationExpr) =>
          sb.append(s"  given $tableGiven: Schema[${table.scalaName}] =\n")
          sb.append(s"    DeriveSchema.gen[${table.scalaName}].validation(\n")
          sb.append(indentLines(validationExpr, 6))
          sb.append("\n    )\n")
        case None =>
          sb.append(s"  given $tableGiven: Schema[${table.scalaName}] = DeriveSchema.gen[${table.scalaName}]\n")

      val primaryKeyColumns = table.columns.filter(_.db.isPartOfPrimaryKey)
      if (primaryKeyColumns.nonEmpty) then
        val idGiven      = schemaGivenName(s"${table.scalaName}.Id")
        val schemaSource = renderIdSchemaSource(table.scalaName, primaryKeyColumns)
        sb.append(s"  given $idGiven: Schema[${table.scalaName}.Id] = $schemaSource\n")

      if (!table.isView) {}
    }

    sb.append("}\n")
    sb.toString
  }

  private final case class TableValidationRender(
    expressions: Seq[String],
    warnings: Seq[String],
  )

  private def renderEnums(schema: DataSchema): String = {
    if schema.enums.isEmpty then ""
    else
      val sb = new StringBuilder
      schema.enums.foreach { dataEnum =>
        sb.append(s"enum ${dataEnum.scalaName}(val value: String):\n")
        dataEnum.values.foreach { value =>
          sb.append(s"  case ${value.scalaName} extends ${dataEnum.scalaName}(\"${escapeScalaString(value.name)}\")\n")
        }
        sb.append("\n")
        sb.append(s"object ${dataEnum.scalaName}:\n")
        sb.append(s"  given Schema[${dataEnum.scalaName}] = DeriveSchema.gen[${dataEnum.scalaName}]\n\n")
      }
      sb.toString
  }

  private def buildTableValidations(table: DataTable): TableValidationRender = {
    val expressions = ListBuffer.empty[String]
    val warnings    = ListBuffer.empty[String]

    table.checkConstraints.filter(_.scope != CheckScope.Column).foreach { constraint =>
      val definition = constraint.expression
      if definition.nonEmpty then
        warnings += s"Unprocessed table-level check '${constraint.name}' on ${table.name}: $definition"
    }

    table.columns.foreach { column =>
      column.checkConstraints.foreach { constraint =>
        val definition = constraint.expression
        if definition.nonEmpty then
          CheckConstraintParser.parse(definition, column.name) match
            case Right(parsed) =>
              renderValidationFromPlan(table, column, parsed.plan) match
                case Some(rendered) => expressions += rendered
                case None =>
                  warnings += s"Unsupported column check '${constraint.name}' on ${table.name}.${column.name}: ${parsed.normalizedExpression}"
            case Left(reason) =>
              warnings += s"Failed to parse column check '${constraint.name}' on ${table.name}.${column.name}: $reason"
      }
    }

    TableValidationRender(expressions.distinct.toSeq, warnings.distinct.toSeq)
  }

  private def renderValidationFromPlan(
    table: DataTable,
    column: DataColumn,
    plan: ValidationPlan,
  ): Option[String] = {
    val columnType = renderColumnType(column)
    val baseType   = renderColumnType(column, forceRequired = true)
    val isOptional = columnType.startsWith("Option[")

    plan match
      case ValidationPlan.NumericComparison(_, operator, value) =>
        renderNumericComparison(table, column, baseType, isOptional, operator, value)
      case ValidationPlan.Between(_, lower, upper, lowerInclusive, upperInclusive) =>
        renderBetweenComparison(table, column, baseType, isOptional, lower, upper, lowerInclusive, upperInclusive)
      case ValidationPlan.LengthComparison(_, operator, value) =>
        renderLengthValidation(table, column, baseType, isOptional, operator, value)
      case ValidationPlan.Inclusion(_, values, negated) =>
        renderInclusionValidation(table, column, baseType, isOptional, values, negated)
      case ValidationPlan.NotNull(_, negated) =>
        renderNotNullValidation(table, column, baseType, isOptional, negated)
  }

  private def renderNumericComparison(
    table: DataTable,
    column: DataColumn,
    baseType: String,
    isOptional: Boolean,
    operator: ComparisonOperator,
    value: LiteralValue,
  ): Option[String] =
    literalFor(baseType, value).map { literal =>
      val baseValidation = operator match
        case ComparisonOperator.GreaterThan        => s"Validation.greaterThan[$baseType]($literal)"
        case ComparisonOperator.GreaterThanOrEqual =>
          s"(Validation.greaterThan[$baseType]($literal) || Validation.equalTo[$baseType]($literal))"
        case ComparisonOperator.LessThan           => s"Validation.lessThan[$baseType]($literal)"
        case ComparisonOperator.LessThanOrEqual    =>
          s"(Validation.lessThan[$baseType]($literal) || Validation.equalTo[$baseType]($literal))"
        case ComparisonOperator.Equal              => s"Validation.equalTo[$baseType]($literal)"
        case ComparisonOperator.NotEqual           => s"!Validation.equalTo[$baseType]($literal)"

      val optionalApplied = applyOptional(baseValidation, isOptional)
      wrapContramap(optionalApplied, table.scalaName, column.scalaName)
    }

  private def renderBetweenComparison(
    table: DataTable,
    column: DataColumn,
    baseType: String,
    isOptional: Boolean,
    lower: LiteralValue,
    upper: LiteralValue,
    lowerInclusive: Boolean,
    upperInclusive: Boolean,
  ): Option[String] =
    for {
      lowerLiteral <- literalFor(baseType, lower)
      upperLiteral <- literalFor(baseType, upper)
    } yield {
      val lowerExpr =
        if lowerInclusive then
          s"(Validation.greaterThan[$baseType]($lowerLiteral) || Validation.equalTo[$baseType]($lowerLiteral))"
        else
          s"Validation.greaterThan[$baseType]($lowerLiteral)"

      val upperExpr =
        if upperInclusive then
          s"(Validation.lessThan[$baseType]($upperLiteral) || Validation.equalTo[$baseType]($upperLiteral))"
        else
          s"Validation.lessThan[$baseType]($upperLiteral)"

      val combined =
        s"Validation.allOf[$baseType](\n${indentLines(lowerExpr, 2)},\n${indentLines(upperExpr, 2)}\n)"

      val optionalApplied = applyOptional(combined, isOptional)
      wrapContramap(optionalApplied, table.scalaName, column.scalaName)
    }

  private def renderLengthValidation(
    table: DataTable,
    column: DataColumn,
    baseType: String,
    isOptional: Boolean,
    operator: ComparisonOperator,
    value: Int,
  ): Option[String] =
    if normalizeBaseType(baseType) != "String" then None
    else
      val baseExprOpt = operator match
        case ComparisonOperator.GreaterThan => Some(s"Validation.minLength(${value + 1})")
        case ComparisonOperator.GreaterThanOrEqual => Some(s"Validation.minLength($value)")
        case ComparisonOperator.LessThan => Some(s"Validation.maxLength(${math.max(0, value - 1)})")
        case ComparisonOperator.LessThanOrEqual => Some(s"Validation.maxLength($value)")
        case ComparisonOperator.Equal =>
          val minExpr = s"Validation.minLength($value)"
          val maxExpr = s"Validation.maxLength($value)"
          Some(s"Validation.allOf[String](\n${indentLines(minExpr, 2)},\n${indentLines(maxExpr, 2)}\n)")
        case ComparisonOperator.NotEqual => None

      baseExprOpt.map { baseExpr =>
        val optionalApplied = applyOptional(baseExpr, isOptional)
        wrapContramap(optionalApplied, table.scalaName, column.scalaName)
      }

  private def renderInclusionValidation(
    table: DataTable,
    column: DataColumn,
    baseType: String,
    isOptional: Boolean,
    values: Seq[LiteralValue],
    negated: Boolean,
  ): Option[String] = {
    val renderedValues = values.flatMap(literalFor(baseType, _))
    if renderedValues.isEmpty then None
    else {
      val baseExpr = renderedValues.toList match
        case single :: Nil if !negated => s"Validation.equalTo[$baseType](${single})"
        case single :: Nil if negated  => s"!Validation.equalTo[$baseType](${single})"
        case many =>
          val terms =
            if negated then many.map(value => s"!Validation.equalTo[$baseType]($value)")
            else many.map(value => s"Validation.equalTo[$baseType]($value)")

          val builder = terms.map(expr => indentLines(expr, 2)).mkString(",\n")
          if negated then s"Validation.allOf[$baseType](\n$builder\n)" else s"Validation.anyOf[$baseType](\n$builder\n)"

      val optionalApplied = applyOptional(baseExpr, isOptional)
      Some(wrapContramap(optionalApplied, table.scalaName, column.scalaName))
    }
  }

  private def renderNotNullValidation(
    table: DataTable,
    column: DataColumn,
    baseType: String,
    isOptional: Boolean,
    negated: Boolean,
  ): Option[String] =
    if !isOptional then None
    else if negated then
      val baseExpr        = s"Validation.succeed[$baseType]"
      val optionalApplied = applyOptional(baseExpr, isOptional = true, allowNone = false)
      Some(wrapContramap(optionalApplied, table.scalaName, column.scalaName))
    else None

  private def literalFor(baseType: String, literal: LiteralValue): Option[String] = {
    val normalized = normalizeBaseType(baseType)
    normalized match
      case "Int" => literal match
          case LiteralValue.Numeric(value) => value.toBigIntExact.map(_.intValue).map(_.toString)
          case _                           => None
      case "Long" => literal match
          case LiteralValue.Numeric(value) => value.toBigIntExact.map(_.longValue).map(v => s"${v}L")
          case _                           => None
      case "Short" => literal match
          case LiteralValue.Numeric(value) => value.toBigIntExact.map(_.intValue).map(v => s"${v}.toShort")
          case _                           => None
      case "Byte" => literal match
          case LiteralValue.Numeric(value) => value.toBigIntExact.map(_.intValue).map(v => s"${v}.toByte")
          case _                           => None
      case "Double" => literal match
          case LiteralValue.Numeric(value) => Some(s"${value.toDouble}d")
          case _                           => None
      case "Float" => literal match
          case LiteralValue.Numeric(value) => Some(s"${value.toFloat}f")
          case _                           => None
      case "BigDecimal" => literal match
          case LiteralValue.Numeric(value)       => Some(s"""BigDecimal("${value.toString}")""")
          case LiteralValue.StringLiteral(value) => Some(s"""BigDecimal("${escapeScalaString(value)}")""")
          case _                                 => None
      case "Boolean" => literal match
          case LiteralValue.BooleanLiteral(value) => Some(value.toString)
          case _                                   => None
      case "String" => literal match
          case LiteralValue.StringLiteral(value) => Some(s"\"${escapeScalaString(value)}\"")
          case LiteralValue.Numeric(value)       => Some(s"\"${escapeScalaString(value.toString)}\"")
          case LiteralValue.BooleanLiteral(value) => Some(s"\"${escapeScalaString(value.toString)}\"")
          case LiteralValue.Raw(value)           => Some(s"\"${escapeScalaString(value)}\"")
      case _ => None
  }

  private def normalizeBaseType(baseType: String): String =
    baseType match
      case "scala.Int" | "java.lang.Integer" | "Int"   => "Int"
      case "scala.Long" | "java.lang.Long" | "Long"    => "Long"
      case "scala.Short" | "java.lang.Short" | "Short" => "Short"
      case "scala.Byte" | "java.lang.Byte" | "Byte"    => "Byte"
      case "scala.Double" | "java.lang.Double" | "Double" => "Double"
      case "scala.Float" | "java.lang.Float" | "Float"   => "Float"
      case "scala.Boolean" | "java.lang.Boolean" | "Boolean" => "Boolean"
      case "java.math.BigDecimal" | "BigDecimal" => "BigDecimal"
      case "java.lang.String" | "String"        => "String"
      case other                                   => other

  private def applyOptional(validationExpr: String, isOptional: Boolean, allowNone: Boolean = true): String =
    if isOptional then
      val methodCall = if allowNone then "optional()" else "optional(validNone = false)"
      s"($validationExpr).$methodCall"
    else validationExpr

  private def wrapContramap(validationExpr: String, tableType: String, accessor: String): String =
    s"($validationExpr).contramap[$tableType](_.${accessor})"

  private def combineValidationExpressions(tableName: String, expressions: Seq[String]): Option[String] =
    expressions.distinct.toList match
      case Nil           => None
      case single :: Nil => Some(single)
      case many          =>
        val body = many.map(expr => indentLines(expr, 2)).mkString(",\n")
        Some(s"Validation.allOf[$tableName](\n$body\n)")

  private def indentLines(value: String, indent: Int): String = {
    val padding = " " * indent
    value.linesIterator.mkString(padding, s"\n$padding", "")
  }

  private def escapeScalaString(value: String): String =
    value.flatMap {
      case '\\' => "\\\\"
      case '\"' => "\\\""
      case '\n' => "\\n"
      case '\r' => "\\r"
      case '\t' => "\\t"
      case c if c.isControl => f"\\u${c.toInt}%04x"
      case c => c.toString
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