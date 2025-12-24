package dbcodegen.generator

import dbcodegen.CodeGeneratorConfig
import zio.ZIO

import java.nio.file.Path

final class DbCodegen(val parameters: DbCodegenParameters)
    extends HasParameters
    with SchemaGenerator:

  def run(
    jdbcUrl: String,
    username: Option[String],
    password: Option[String],
    config: CodeGeneratorConfig,
  ): ZIO[DbModelLoader & Generator & CodeFileGenerator, GeneratorFailure[DbCodegenFailure], Set[Path]] =
    for {
      model <- DbModelLoader
                 .load(jdbcUrl, username, password, config)
                 .mapError(err => GeneratorFailure.UserError(err, Some("dbcodegen introspection")))
      out   <- generateSchemas(model, config)
    } yield out

