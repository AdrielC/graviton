package dbcodegen.generator

import dbcodegen.{CodeGeneratorConfig, DbConnectionSource, SchemaConverter}
import schemacrawler.schemacrawler.SchemaCrawlerOptionsBuilder
import schemacrawler.tools.utility.SchemaCrawlerUtility
import us.fatehi.utility.datasource.DatabaseConnectionSource
import zio.{ULayer, ZIO, ZLayer}

import scala.jdk.CollectionConverters.*

trait DbModelLoader:
  def load(
    jdbcUrl: String,
    username: Option[String],
    password: Option[String],
    config: CodeGeneratorConfig,
  ): ZIO[Any, DbCodegenFailure, DbModel]

object DbModelLoader:

  val live: ULayer[DbModelLoader] =
    ZLayer.succeed(
      new DbModelLoader:
        override def load(
          jdbcUrl: String,
          username: Option[String],
          password: Option[String],
          config: CodeGeneratorConfig,
        ): ZIO[Any, DbCodegenFailure, DbModel] =
          ZIO.attempt {
            val ds: DatabaseConnectionSource = DbConnectionSource(jdbcUrl, username, password)

            val crawlOpts = SchemaCrawlerOptionsBuilder.newSchemaCrawlerOptions()
            val retrOpts  = SchemaCrawlerUtility.matchSchemaRetrievalOptions(ds)
            val catalog   = SchemaCrawlerUtility.getCatalog(ds, retrOpts, crawlOpts, config.schemaCrawlerOptions.toConfig)

            val schemas = catalog.getSchemas().asScala.toSeq

            val schemasData =
              schemas.flatMap { schema =>
                val schemaName = Option(schema.getName).getOrElse("")
                if (!config.isSchemaIncluded(schemaName)) None
                else {
                  val tables = catalog.getTables(schema).asScala.toSeq
                  val dataSchema = SchemaConverter.toDataSchema(schema, ds, tables, config)
                  if (dataSchema.tables.nonEmpty || dataSchema.enums.nonEmpty) Some(dataSchema) else None
                }
              }

            DbModel(schemasData)
          }.mapError(e => DbCodegenFailure.IntrospectionFailed("Failed to introspect database schema", Some(e)))
    )

  def load(
    jdbcUrl: String,
    username: Option[String],
    password: Option[String],
    config: CodeGeneratorConfig,
  ): ZIO[DbModelLoader, DbCodegenFailure, DbModel] =
    ZIO.serviceWithZIO[DbModelLoader](_.load(jdbcUrl, username, password, config))

