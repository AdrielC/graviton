package dbcodegen

import java.nio.file.{Path, Files}
import java.io.File
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
    sb.append("import com.augustnagro.magnum.pg.enums.*\n")
    sb.append("import graviton.pg.given\n")
    sb.append("import graviton.pg.PgRange\n")
    sb.append("import io.github.iltotore.iron.*\n")
    sb.append("import io.github.iltotore.iron.constraint.all.*\n")
    sb.append("import zio.schema.*\n")
    sb.append("import zio.schema.annotation.*\n\n")
    
    // Generate Iron type aliases for common constraints
    sb.append("// Iron Type Aliases for Database Constraints\n")
    sb.append("type NonEmptyString = String :| MinLength[1]\n")
    sb.append("type EmailString = String :| Match[\"^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\\\.[a-zA-Z]{2,}$\"]\n")
    sb.append("type PositiveInt = Int :| Positive\n")
    sb.append("type NonNegativeInt = Int :| GreaterEqual[0]\n")
    sb.append("type UuidString = String :| Match[\"^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$\"]\n\n")

    // Generate enums
    schema.enums.foreach { e =>
      sb.append(s"enum ${e.scalaName} derives DbCodec:\n")
      e.values.foreach { v =>
        sb.append(s"  @SqlName(\"${v.name}\")\n")
        sb.append(s"  case ${v.scalaName}\n")
      }
      sb.append("\n")
    }

    // Generate tables
    schema.tables.foreach { t =>
      sb.append("@Table(PostgresDbType)\n")
      sb.append(s"case class ${t.scalaName}(\n")
      val cols = t.columns
      cols.foreach { c =>
        if (c.db.isPartOfPrimaryKey) sb.append("  @Id\n")
        sb.append(s"  @SqlName(\"${c.name}\")\n")
        val ironType = mapToIronType(c)
        sb.append(s"  ${c.scalaName}: $ironType,\n")
      }
      sb.append(") derives DbCodec\n\n")
      
      sb.append(s"object ${t.scalaName}:\n")
      val pkCols = cols.filter(_.db.isPartOfPrimaryKey)
      val idTpe = if (pkCols.isEmpty) "Null" else pkCols.map(_.scalaType).mkString("(", ", ", ")")
      sb.append(s"  type Id = $idTpe\n\n")

      if (!t.isView) {
        sb.append(s"  case class Creator(\n")
        val creatorCols = cols.filter { c =>
          val col = c.db
          !col.isGenerated && !col.isAutoIncremented && !col.hasDefaultValue
        }
        creatorCols.foreach { c =>
          val ironType = mapToIronType(c)
          sb.append(s"    ${c.scalaName}: $ironType,\n")
        }
        sb.append("  ) derives DbCodec\n\n")
      }
      
      if (t.isView)
        sb.append(s"  val ${t.scalaName}Repo = ImmutableRepo[${t.scalaName}, ${t.scalaName}.Id]\n\n")
      else
        sb.append(s"  val ${t.scalaName}Repo = Repo[${t.scalaName}.Creator, ${t.scalaName}, ${t.scalaName}.Id]\n\n")
    }
    
    // Add ZIO Schema definitions
    sb.append(generateZioSchema(schema))
    
    sb.toString
  }

  private def mapToIronType(column: DataColumn): String = {
    val baseType = column.scalaType
    val dbColumn = column.db
    
    // Check for common patterns that can be mapped to Iron types
    val columnName = column.name.toLowerCase
    val _ = dbColumn.getParent.getName.toLowerCase
    
    baseType match {
      case "String" =>
        // Check for common patterns
        if (columnName.contains("email")) "EmailString"
        else if (columnName.contains("uuid") || columnName.contains("id") && !dbColumn.isPartOfPrimaryKey) "UuidString"
        else if (!dbColumn.isNullable && dbColumn.getSize > 0) s"String :| MinLength[1] & MaxLength[${dbColumn.getSize}]"
        else if (!dbColumn.isNullable) "NonEmptyString"
        else baseType
        
      case "Int" =>
        if (!dbColumn.isNullable && columnName.contains("count") || columnName.contains("size") || columnName.contains("length")) "NonNegativeInt"
        else if (!dbColumn.isNullable && (columnName.contains("id") && dbColumn.isPartOfPrimaryKey)) "PositiveInt"
        else baseType
        
      case _ => baseType
    }
  }

  private def generateZioSchema(schema: DataSchema): String = {
    val sb = new StringBuilder
    sb.append(s"\n// ZIO Schema definitions for ${schema.name}\n")
    sb.append("object Schemas {\n")
    
    schema.tables.foreach { table =>
      sb.append(s"  given Schema[${table.scalaName}] = DeriveSchema.gen[${table.scalaName}]\n")
      if (!table.isView) {
        sb.append(s"  given Schema[${table.scalaName}.Creator] = DeriveSchema.gen[${table.scalaName}.Creator]\n")
      }
    }
    
    schema.enums.foreach { enumDef =>
      sb.append(s"  given Schema[${enumDef.scalaName}] = DeriveSchema.gen[${enumDef.scalaName}]\n")
    }
    
    sb.append("}\n")
    sb.toString
  }
}