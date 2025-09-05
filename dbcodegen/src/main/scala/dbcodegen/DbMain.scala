package dbcodegen

import java.io.File

import java.util.logging.Level
import scala.util.chaining.*


object DbMain {

  private lazy val log = java.util.logging.Logger.getGlobal().tap(_.setLevel(Level.WARNING))

  def main(args: Array[String]): Unit = {

    val jdbcUrl        = sys.props.getOrElse("dbcodegen.jdbcUrl", sys.env.getOrElse("PG_JDBC_URL", "jdbc:postgresql://127.0.0.1:5432/postgres"))
    val username       = sys.props.get("dbcodegen.username").orElse(sys.env.get("PG_USERNAME")).orElse(Some("postgres"))
    val password       = sys.props.get("dbcodegen.password").orElse(sys.env.get("PG_PASSWORD")).orElse(Some("postgres"))
    val templatePath   = sys.props.getOrElse("dbcodegen.template", "modules/pg/codegen/magnum.ssp")
    val outputPath     = sys.props.getOrElse("dbcodegen.out", "modules/pg/src/main/resources/generated")
    val inspectOnly    = sys.props.get("dbcodegen.inspect-only").contains("true")

    def resolve(path: String): File = {
      val p = new File(path)
      if (p.isAbsolute) p
      else {
        val cwd = new File(".").getCanonicalFile
        val parents = List(cwd, cwd.getParentFile, Option(cwd.getParentFile)
        .flatMap(p => Option(p.getParentFile)).orNull).filter(_ != null)
        parents.map(new File(_, path)).find(_.exists()).getOrElse(new File(cwd, path))
      }
    }

    log.info(s"""
    |=== Database Code Generation ===
    |  JDBC URL: $jdbcUrl
    |  Username: $username
    |  Template: $templatePath  
    |  Output: $outputPath
    |=====================================
    """.stripMargin)

    val template  = resolve(templatePath)
      .tap(f => log.info(s"✓ Template: ${f.getAbsolutePath}"))

    val outDir    = resolve(outputPath)
      .tap(f => if (!f.exists()) then f.mkdirs : Unit)
      .tap(_.mkdirs())
      .tap(f => log.info(s"✓ Output directory: ${f.getAbsolutePath}"))


    if (inspectOnly) {
      log.info("🔍 Running in inspect-only mode - constraints will be logged but no files generated")
      val config = CodeGeneratorConfig.default.copy(
        templateFiles = Seq.empty,
        outDir = outDir.toPath()
      )
      
      CodeGenerator.generate(
        jdbcUrl         = jdbcUrl,
        username        = username,
        password        = password,
        config          = config
      ): Unit
    } else {
      val config = CodeGeneratorConfig.default.copy(
        templateFiles = Seq(template),
        outDir = outDir.toPath()
      ).tap(c => log.info(s"config: $c"))

      CodeGenerator.generate(
        jdbcUrl         = jdbcUrl,
        username        = username,
        password        = password,
        config          = config
      )
      .tap:
        _.pipe(_.length)
        .tap(count => log.info(s"🎉 Generated ${count} files into ${outDir.getAbsolutePath}"))
      : Unit
    }
  }
}
