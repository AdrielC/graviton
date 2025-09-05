package dbcodegen

import java.io.File

object DbMain {
  def main(args: Array[String]): Unit = {
    val jdbcUrl        = sys.props.getOrElse("dbcodegen.jdbcUrl", sys.env.getOrElse("PG_JDBC_URL", "jdbc:postgresql://127.0.0.1:5432/postgres"))
    val username       = sys.props.get("dbcodegen.username").orElse(sys.env.get("PG_USERNAME")).orElse(Some("postgres"))
    val password       = sys.props.get("dbcodegen.password").orElse(sys.env.get("PG_PASSWORD")).orElse(Some("postgres"))
    val templatePath   = sys.props.getOrElse("dbcodegen.template", "modules/pg/codegen/magnum.ssp")
    val outputPath     = sys.props.getOrElse("dbcodegen.out", "modules/pg/src/main/scala/graviton/db")

    val outDir    = new File(outputPath)
    val template  = new File(templatePath)

    outDir.mkdirs()

    val config = CodeGeneratorConfig.default.copy(
      templateFiles = Seq(template),
      outDir = outDir
    )

    val paths = CodeGenerator.generate(
      jdbcUrl         = jdbcUrl,
      username        = username,
      password        = password,
      templateFiles   = Seq(template),
      outputDirectory = outDir,
      config          = config
    )()

    val count = paths.length
    println(s"Generated ${count} files into ${outDir.getAbsolutePath}")
  }
}


