package dbcodegen

import java.nio.file.Path
import java.io.File
import scala.collection.immutable.Seq
import schemacrawler.tools.utility.SchemaCrawlerUtility
import schemacrawler.schemacrawler.SchemaCrawlerOptionsBuilder

object CodeGenerator {
  def generate(
    jdbcUrl: String,
    username: Option[String],
    password: Option[String],
    templateFiles: Seq[File],
    outputDirectory: File,
    config: CodeGeneratorConfig,
  )(log: java.util.logging.Logger = java.util.logging.Logger.getLogger("dbcodegen")): Seq[Path] = {
    val _ = (log, templateFiles)
    val ds = new DbConnectionSource(jdbcUrl, username, password)

    val crawlOpts = SchemaCrawlerOptionsBuilder.newSchemaCrawlerOptions()
    val retrOpts  = SchemaCrawlerUtility.matchSchemaRetrievalOptions(ds)
    val catalog   = SchemaCrawlerUtility.getCatalog(ds, retrOpts, crawlOpts, new schemacrawler.tools.options.Config())

    val schemas = catalog.getSchemas().toArray(new Array[schemacrawler.schema.Schema](0)).toSeq
    val tablesPerSchema = schemas.map { s =>
      val tables = catalog.getTables(s)
      (s, tables)
    }

    val generated = tablesPerSchema.flatMap { case (schema, tbls) =>
      val data    = SchemaConverter.toDataSchema(schema, ds, tbls.toArray(new Array[schemacrawler.schema.Table](0)).toSeq, config.copy(outDir = outputDirectory))
      val outFile = new File(outputDirectory, s"${data.scalaName}.scala")
      val parent  = outFile.getParentFile
      if (!parent.exists()) { parent.mkdirs(); () }
      val code    = renderDataSchema(data)
      val bytes   = code.getBytes(java.nio.charset.StandardCharsets.UTF_8)
      java.nio.file.Files.write(outFile.toPath, bytes)
      Seq(outFile.toPath)
    }
    generated
  }

  private def renderDataSchema(schema: DataSchema): String = {
    val sb = new StringBuilder
    sb.append("package graviton.pg.generated\n\n")
    sb.append("import com.augustnagro.magnum.*\n")
    sb.append("import com.augustnagro.magnum.pg.enums.*\n")
    sb.append("import graviton.pg.given\n")
    sb.append("import graviton.pg.PgRange\n\n")

    // Enums
    schema.enums.foreach { e =>
      sb.append(s"enum ${e.scalaName} derives DbCodec:\n")
      e.values.foreach { v =>
        sb.append(s"  @SqlName(\"${v.name}\")\n")
        sb.append(s"  case ${v.scalaName}\n")
      }
      sb.append("\n")
    }

    // Tables
    schema.tables.foreach { t =>
      sb.append("@Table(PostgresDbType)\n")
      sb.append(s"case class ${t.scalaName}(\n")
      val cols = t.columns
      cols.foreach { c =>
        if (c.db.isPartOfPrimaryKey) sb.append("  @Id\n")
        sb.append(s"  @SqlName(\"${c.name}\")\n")
        sb.append(s"  ${c.scalaName}: ${c.scalaType},\n")
      }
      sb.append(") derives DbCodec\n")
      sb.append(s"object ${t.scalaName}:\n")
      val pkCols = cols.filter(_.db.isPartOfPrimaryKey)
      val idTpe  = if (pkCols.isEmpty) "Null" else pkCols.map(_.scalaType).mkString("(", ", ", ")")
      sb.append(s"  type Id = ${idTpe}\n\n")

      if (!t.isView) {
        sb.append(s"  case class Creator(\n")
        val creatorCols = cols.filter { c =>
          val col = c.db
          !col.isGenerated && !col.isAutoIncremented && !col.hasDefaultValue
        }
        creatorCols.foreach { c =>
          sb.append(s"    ${c.scalaName}: ${c.scalaType},\n")
        }
        sb.append("  ) derives DbCodec\n")
      }
      if (t.isView)
        sb.append(s"  val ${t.scalaName}Repo = ImmutableRepo[${t.scalaName}, ${t.scalaName}.Id]\n\n")
      else
        sb.append(s"  val ${t.scalaName}Repo = Repo[${t.scalaName}.Creator, ${t.scalaName}, ${t.scalaName}.Id]\n\n")
    }
    sb.toString
  }
}


