package dbcodegen

import java.nio.file.Path
import java.io.File
import scala.collection.immutable.Seq
import schemacrawler.tools.utility.SchemaCrawlerUtility
import org.fusesource.scalate.TemplateEngine
import org.fusesource.scalate.support.FileTemplateSource
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
    val _ = log
    val ds = new DbConnectionSource(jdbcUrl, username, password)

    val crawlOpts = SchemaCrawlerOptionsBuilder.newSchemaCrawlerOptions()
    val retrOpts  = SchemaCrawlerUtility.matchSchemaRetrievalOptions(ds)
    val catalog   = SchemaCrawlerUtility.getCatalog(ds, retrOpts, crawlOpts, new schemacrawler.tools.options.Config())

    val schemas = catalog.getSchemas().toArray(new Array[schemacrawler.schema.Schema](0)).toSeq
    val tablesPerSchema = schemas.map { s =>
      val tables = catalog.getTables(s)
      (s, tables)
    }

    val engine = new TemplateEngine
    val generated = tablesPerSchema.flatMap { case (schema, tbls) =>
      val data = SchemaConverter.toDataSchema(schema, ds, tbls.toArray(new Array[schemacrawler.schema.Table](0)).toSeq, config.copy(templateFiles = templateFiles, outDir = outputDirectory))
      templateFiles.flatMap { tf =>
        val src   = FileTemplateSource(tf, tf.getPath)
        val out   = new File(outputDirectory, s"${data.scalaName}.scala")
        val code  = engine.layout(src, Map("schema" -> data))
        val parent = out.getParentFile
        if (!parent.exists()) { parent.mkdirs(); () }
        val bytes = code.getBytes(java.nio.charset.StandardCharsets.UTF_8)
        java.nio.file.Files.write(out.toPath, bytes)
        Seq(out.toPath)
      }
    }
    generated
  }
}


