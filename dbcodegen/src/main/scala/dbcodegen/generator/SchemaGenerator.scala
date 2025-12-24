package dbcodegen.generator

import dbcodegen.{CodeGenerator, CodeGeneratorConfig, OutputLayout}
import zio.{ZIO}

import java.nio.file.Path

trait SchemaGenerator:
  this: HasParameters =>

  def generateSchemas(
    model: DbModel,
    config: CodeGeneratorConfig,
  ): ZIO[Generator & CodeFileGenerator, GeneratorFailure[DbCodegenFailure], Set[Path]] = {

    val generate =
      for {
        _ <- Generator.setRoot(parameters.targetRoot)
        _ <- Generator.setScalaVersion(parameters.scalaVersion)
        root <- zio.ZIO.serviceWithZIO[Generator](_.root)
        files <- ZIO.foreach(model.schemas) { schema =>
                   val (subPackage, fileName) =
                     config.outputLayout match
                       case OutputLayout.PerSchemaDirectory => (schema.name, "schema.scala")
                       case OutputLayout.FlatFiles          => ("", s"${schema.name}.scala")

                   val render =
                     ZIO
                       .fromEither(CodeGenerator.renderScalaCode(schema, config))
                       .mapError(err => DbCodegenFailure.RenderFailed(err.message))

                   if (config.dryRun) {
                     val outDir  = Generator.packageDirFor(root, config.basePackage, subPackage)
                     val outPath = outDir.resolve(fileName)
                     render.as(outPath).mapError(e => GeneratorFailure.UserError(e, Some("dbcodegen render")))
                   } else {
                     Generator.writeScalaPackage[DbCodegenFailure](
                       basePackage = config.basePackage,
                       subPackage = subPackage,
                       fileName = fileName,
                     )(render)
                   }
                 }
      } yield files.toSet

    generate
  }

