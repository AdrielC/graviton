package dbcodegen

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import munit.FunSuite

import java.io.File
import java.nio.file.Files

import zio.*
import zio.test.*

object CodeGeneratorIntegrationSuite extends ZIOSpec[EmbeddedPostgres]:

  override def bootstrap: ZLayer[Any, Nothing, EmbeddedPostgres] =
    ZLayer.scoped:
      ZIO.acquireRelease(
        ZIO.attempt(EmbeddedPostgres.builder().setPort(0).start()).orDie
      )(pg => ZIO.attempt(pg.close()).ignoreLogged)

  def spec = suite("CodeGeneratorIntegrationSuite")(
    test("code generation matches checked-in snapshot")({
      for
        postgres <- ZIO.service[EmbeddedPostgres]
        connection <- ZIO.fromAutoCloseable(ZIO.attempt(postgres.getPostgresDatabase.getConnection))
        _ <- SqlExecutor.executeSqlFile(connection, new File("modules/pg/ddl.sql"))
        tmpDir <- ZIO.attempt(Files.createTempDirectory("dbcodegen-test"))
        config = CodeGeneratorConfig.default.copy(outDir = tmpDir, templateFiles = Seq.empty)
        generated <- CodeGenerator.generate(
          jdbcUrl = postgres.getJdbcUrl("postgres", "postgres"),
          username = Some("postgres"),
          password = Some("postgres"),config = config
        )

        expected <- ZIO.attempt(java.nio.file.Path.of("modules/pg/src/main/scala/graviton/pg/generated/public.scala"))
          .filterOrFail(path => Files.exists(path))( Throwable("expected file does not exist"))
        
        rendered <- ZIO.attempt(generated.head)

        generatedSource <- ZIO.attempt(Files.readString(rendered).trim)
        expectedSource  <- ZIO.attempt(Files.readString(expected).trim)

        _ <- Console.printLine(s"Generated source: $generatedSource")
        _ <- Console.printLine(s"Expected source: $expectedSource")
        
      yield assertTrue(generatedSource == expectedSource)

    })

  )
end CodeGeneratorIntegrationSuite
