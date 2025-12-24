package dbcodegen

import java.io.File
import java.util.logging.Level

import scala.util.chaining.scalaUtilChainingOps
import scala.util.matching.Regex

object DbMain {

  private lazy val log = java.util.logging.Logger.getGlobal.tap(_.setLevel(Level.WARNING))

  def main(args: Array[String]): Unit = {
    val jdbcUrl = sys.props
      .get("dbcodegen.jdbcUrl")
      .orElse(sys.env.get("PG_JDBC_URL"))
      .getOrElse("jdbc:postgresql://127.0.0.1:5432/postgres")

    val username = sys.props
      .get("dbcodegen.username")
      .orElse(sys.env.get("PG_USERNAME"))
      .orElse(Some("postgres"))

    val password = sys.props
      .get("dbcodegen.password")
      .orElse(sys.env.get("PG_PASSWORD"))
      .orElse(Some("postgres"))

    val outputPath  = sys.props.getOrElse("dbcodegen.out", "modules/pg/src/main/scala/graviton/pg/generated")
    val inspectOnly = sys.props.get("dbcodegen.inspect-only").contains("true")
    val basePackage = sys.props.getOrElse("dbcodegen.basePackage", CodeGeneratorConfig.default.basePackage)
    val layout = sys.props
      .get("dbcodegen.layout")
      .flatMap(OutputLayout.fromString)
      .getOrElse(CodeGeneratorConfig.default.outputLayout)
    val inspectConstraints = sys.props.get("dbcodegen.inspect-constraints").contains("true")

    val includeSchemas =
      sys.props
        .get("dbcodegen.schemas")
        .orElse(sys.props.get("dbcodegen.includeSchemas"))
        .map(_.split(",").iterator.map(_.trim).filter(_.nonEmpty).toSet)
        .getOrElse(Set("core", "graviton", "quasar"))

    val excludeSchemas =
      sys.props
        .get("dbcodegen.excludeSchemas")
        .map(_.split(",").iterator.map(_.trim).filter(_.nonEmpty).toSet)
        .getOrElse(CodeGeneratorConfig.default.excludeSchemas)

    val includeTablePattern: Option[Regex] =
      sys.props.get("dbcodegen.includeTablesRegex").map(_.r)

    val excludeTablePattern: Option[Regex] =
      sys.props.get("dbcodegen.excludeTablesRegex").map(_.r).orElse(CodeGeneratorConfig.default.excludeTablePattern)

    val outDir = resolvePath(outputPath).tap { dir =>
      if (!dir.exists()) {
        val _ = dir.mkdirs()
      }
    }

    log.info(
      s"""
         |=== Database Code Generation ===
         |  JDBC URL: $jdbcUrl
         |  Username: ${username.getOrElse("<anonymous>")}
         |  Output:   ${outDir.getAbsolutePath}
         |================================
         |""".stripMargin,
    )

    val config = CodeGeneratorConfig.default.copy(
      templateFiles = Seq.empty,
      outDir = outDir.toPath,
      basePackage = basePackage,
      outputLayout = layout,
      includeSchemas = includeSchemas,
      excludeSchemas = excludeSchemas,
      includeTablePattern = includeTablePattern,
      excludeTablePattern = excludeTablePattern,
      inspectConstraints = inspectConstraints,
      dryRun = inspectOnly,
    )

    val resultsE = CodeGenerator.generate(
      jdbcUrl = jdbcUrl,
      username = username,
      password = password,
      config = config,
    )

    resultsE match
      case Left(err) =>
        System.err.println(err.message)
      case Right(results) =>
        if (inspectOnly)
          log.info("Inspect-only mode complete")
        else
          log.info(s"Generated ${results.size} file(s) into ${outDir.getAbsolutePath}")
  }

  private def resolvePath(path: String): File = {
    val file = new File(path)
    if (file.isAbsolute) file
    else {
      val cwd     = new File(".").getCanonicalFile
      val parents = Iterator.iterate(Option(cwd))(_.flatMap(p => Option(p.getParentFile))).flatten.take(3).toSeq
      parents.map(new File(_, path)).find(_.exists()).getOrElse(new File(cwd, path))
    }
  }
}
