package dbcodegen.generator

import java.nio.file.Path

/** Minimal parameter surface, modeled after ZIO codegen style:
  * - explicit target root
  * - explicit scala version (for output layout decisions)
  *
  * DB connectivity + schema selection stays in `dbcodegen.CodeGeneratorConfig`.
  */
final case class DbCodegenParameters(
  targetRoot: Path,
  scalaVersion: String,
)

