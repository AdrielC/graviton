package dbcodegen.generator

import zio.{ULayer, ZLayer}

/** Placeholder service matching the "ZIO codegen" structure.
  *
  * In ZIO's OpenAI generator, this service is responsible for keeping track of
  * file-level concerns (scaladoc rendering, imports, known names, formatting).
  *
  * For dbcodegen we keep it intentionally minimal and evolve it as needed.
  */
trait CodeFileGenerator

object CodeFileGenerator:
  val live: ULayer[CodeFileGenerator] =
    ZLayer.succeed(new CodeFileGenerator {})

