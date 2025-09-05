package dbcodegen

import java.io.File
import java.sql.SQLType

final case class CodeGeneratorConfig(
  templateFiles: Seq[File],
  outDir: File,
  typeMapping: (SQLType, Option[String]) => Option[String],
  schemaTableFilter: (String, String) => Boolean,
  scalafmt: Boolean,
  scalaVersion: String,
)

object CodeGeneratorConfig {
  val default: CodeGeneratorConfig = CodeGeneratorConfig(
    templateFiles = Seq.empty,
    outDir = new File("target/managed-codegen"),
    typeMapping = (_: SQLType, guess: Option[String]) => guess.orElse(Some("String")),
    schemaTableFilter = (_: String, _: String) => true,
    scalafmt = false,
    scalaVersion = scala.util.Properties.versionNumberString,
  )
}


