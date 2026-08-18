package dbcodegen

import java.io.File
import java.util.logging.Level

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres

import scala.util.chaining.scalaUtilChainingOps
import scala.util.matching.Regex
import zio.{Exit, Runtime, Unsafe}

object DbMain {

  private lazy val log = java.util.logging.Logger.getGlobal.tap(_.setLevel(Level.WARNING))

  def main(args: Array[String]): Unit = {
    def propOrEnv(propKey: String, envKey: String): Option[String] =
      sys.props.get(propKey).orElse(sys.env.get(envKey))

    val ddlPathOpt =
      propOrEnv("dbcodegen.ddl", "DBCODEGEN_DDL")
        .orElse(propOrEnv("dbcodegen.ddlPath", "DBCODEGEN_DDL_PATH"))
        .map(resolvePath)

    val jdbcUrlFromEnvOrDefault =
      propOrEnv("dbcodegen.jdbcUrl", "PG_JDBC_URL")
        .getOrElse("jdbc:postgresql://127.0.0.1:5432/postgres")

    val username = sys.props
      .get("dbcodegen.username")
      .orElse(sys.env.get("PG_USERNAME"))
      .orElse(Some("postgres"))

    val password = sys.props
      .get("dbcodegen.password")
      .orElse(sys.env.get("PG_PASSWORD"))
      .orElse(Some("postgres"))

    val outputPath =
      propOrEnv("dbcodegen.out", "DBCODEGEN_OUT")
        .getOrElse("modules/pg/src/main/scala/graviton/pg/generated")

    val inspectOnly =
      propOrEnv("dbcodegen.inspect-only", "DBCODEGEN_INSPECT_ONLY").contains("true")

    val basePackage =
      propOrEnv("dbcodegen.basePackage", "DBCODEGEN_BASE_PACKAGE")
        .getOrElse(CodeGeneratorConfig.default.basePackage)

    val layout = sys.props
      .get("dbcodegen.layout")
      .orElse(sys.env.get("DBCODEGEN_LAYOUT"))
      .flatMap(OutputLayout.fromString)
      .getOrElse(CodeGeneratorConfig.default.outputLayout)
    val inspectConstraints =
      propOrEnv("dbcodegen.inspect-constraints", "DBCODEGEN_INSPECT_CONSTRAINTS").contains("true")

    val includeSchemas =
      propOrEnv("dbcodegen.schemas", "DBCODEGEN_SCHEMAS")
        .orElse(propOrEnv("dbcodegen.includeSchemas", "DBCODEGEN_INCLUDE_SCHEMAS"))
        .map(_.split(",").iterator.map(_.trim).filter(_.nonEmpty).toSet)
        .getOrElse(Set("core", "graviton", "quasar"))

    val excludeSchemas =
      propOrEnv("dbcodegen.excludeSchemas", "DBCODEGEN_EXCLUDE_SCHEMAS")
        .map(_.split(",").iterator.map(_.trim).filter(_.nonEmpty).toSet)
        .getOrElse(CodeGeneratorConfig.default.excludeSchemas)

    val includeTablePattern: Option[Regex] =
      propOrEnv("dbcodegen.includeTablesRegex", "DBCODEGEN_INCLUDE_TABLES_REGEX").map(_.r)

    val excludeTablePattern: Option[Regex] =
      propOrEnv("dbcodegen.excludeTablesRegex", "DBCODEGEN_EXCLUDE_TABLES_REGEX")
        .map(_.r)
        .orElse(CodeGeneratorConfig.default.excludeTablePattern)

    val outDir = resolvePath(outputPath).tap { dir =>
      if (!dir.exists()) {
        val _ = dir.mkdirs()
      }
    }

    log.info(
      s"""
         |=== Database Code Generation ===
         |  JDBC URL: $jdbcUrlFromEnvOrDefault
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

    val targetRootOpt = sys.props.get("dbcodegen.targetRoot").map(resolvePath).map(_.toPath)
    val scalaVersion  = sys.props.getOrElse("dbcodegen.scalaVersion", "3.7.4")

    def runCodegen(jdbcUrl: String): Unit =
      if (targetRootOpt.isDefined) {
      // ZIO-style generator pipeline (inspired by zio-openai-codegen).
      val targetRoot = targetRootOpt.get
      val codegen    = new dbcodegen.generator.DbCodegen(
        dbcodegen.generator.DbCodegenParameters(
          targetRoot = targetRoot,
          scalaVersion = scalaVersion,
        ),
      )

      val program =
        codegen
          .run(jdbcUrl, username, password, config)
          .provide(
            dbcodegen.generator.DbModelLoader.live,
            dbcodegen.generator.Generator.live,
            dbcodegen.generator.CodeFileGenerator.live,
          )

      Unsafe.unsafe { implicit u =>
        Runtime.default.unsafe.run(program) match
          case Exit.Success(paths) =>
            if (inspectOnly)
              log.info("Inspect-only mode complete")
            else
              log.info(s"Generated ${paths.size} file(s) into $targetRoot")
          case Exit.Failure(cause) =>
            System.err.println(cause.prettyPrint)
      }
    } else {
      // Legacy pipeline: uses `dbcodegen.out` as a concrete output directory.
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

    ddlPathOpt match
      case None =>
        runCodegen(jdbcUrlFromEnvOrDefault)
      case Some(ddlPath) =>
        val postgres = EmbeddedPostgres.builder().setPort(0).start()
        try {
          val connection = postgres.getPostgresDatabase.getConnection
          try SqlExecutor.executeSqlFile(connection, ddlPath)
          finally connection.close()

          val embeddedJdbcUrl = postgres.getJdbcUrl("postgres", username.getOrElse("postgres"))
          runCodegen(embeddedJdbcUrl)
        } finally postgres.close()
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
