package dbcodegen

import java.io.File

object DbMain {
  def main(args: Array[String]): Unit = {
    val jdbcUrl        = sys.props.getOrElse("dbcodegen.jdbcUrl", sys.env.getOrElse("PG_JDBC_URL", "jdbc:postgresql://127.0.0.1:5432/postgres"))
    val username       = sys.props.get("dbcodegen.username").orElse(sys.env.get("PG_USERNAME")).orElse(Some("postgres"))
    val password       = sys.props.get("dbcodegen.password").orElse(sys.env.get("PG_PASSWORD")).orElse(Some("postgres"))
    val templatePath   = sys.props.getOrElse("dbcodegen.template", "../modules/pg/codegen/magnum.ssp")
    val outputPath     = sys.props.getOrElse("dbcodegen.out", "../modules/pg/src/main/scala/graviton/db")

    def resolve(path: String): File = {
      val p = new File(path)
      if (p.isAbsolute) p
      else {
        val cwd = new File(".").getCanonicalFile
        val parents = List(cwd, cwd.getParentFile, Option(cwd.getParentFile).flatMap(p => Option(p.getParentFile)).orNull).filter(_ != null)
        parents.map(new File(_, path)).find(_.exists()).getOrElse(new File(cwd, path))
      }
    }

    val template  = resolve(templatePath)
    val outDir    = {
      val f = resolve(outputPath)
      if (!f.exists()) { f.mkdirs(); () }
      f
    }

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


