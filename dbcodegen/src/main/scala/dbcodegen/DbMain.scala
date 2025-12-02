package dbcodegen

import java.io.File
import zio.*

import scala.util.chaining.scalaUtilChainingOps

object DbMain extends ZIOAppDefault:
  
  def run = ZIO.suspend {

    val jdbcUrl = sys.props
      .get("dbcodegen.jdbcUrl")
      .orElse(sys.env.get("PG_JDBC_URL"))
      .getOrElse {
        
        val PG_HOST = sys.env.get("PG_HOST").getOrElse("127.0.0.1")
        val PG_PORT = sys.env.get("PG_PORT").getOrElse("5432")
        val PG_DATABASE = sys.env.get("PG_DATABASE").getOrElse("graviton")
        
        s"jdbc:postgresql://${PG_HOST}:${PG_PORT}/${PG_DATABASE}" 
      }

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

    val outDir = resolvePath(outputPath).tap { dir =>
      if (!dir.exists()) {
        val _ = dir.mkdirs()
      }
    }

    println(
      s"""
          |=== Database Code Generation ===
          |  JDBC URL: $jdbcUrl
          |  Username: ${username.getOrElse("<anonymous>")}
          |  Password: ${password.getOrElse("<anonymous>")}
          |  Output:   ${outDir.getAbsolutePath}
          |================================
          |""".stripMargin,
    )

    val config = CodeGeneratorConfig.default.copy(
      templateFiles = Seq.empty,
      outDir = outDir.toPath,
    )

    CodeGenerator.generate(
      jdbcUrl = jdbcUrl,
      username = username,
      password = password,
      config = config,
    ).tap: results =>
      if (inspectOnly)
        ZIO.logInfo("Inspect-only mode complete")
      else
        ZIO.logInfo(s"🎉 Generated ${results.size} file(s) into ${outDir.getAbsolutePath}")
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

