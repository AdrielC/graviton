package dbcodegen

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import munit.FunSuite

import java.io.File
import java.nio.file.Files

final class CodeGeneratorIntegrationSuite extends FunSuite {

  override def afterAll(): Unit = {
    super.afterAll()
    val _ = System.clearProperty("PG_JDBC_URL")
  }

  test("code generation matches checked-in snapshot") {
    val postgres = EmbeddedPostgres.builder().setPort(0).start()
    try {
      val connection = postgres.getPostgresDatabase.getConnection
      try SqlExecutor.executeSqlFile(connection, new File("modules/pg/ddl.sql"))
      finally connection.close()

      val tmpDir = Files.createTempDirectory("dbcodegen-test")
      val config = CodeGeneratorConfig.default.copy(
        outDir = tmpDir,
        templateFiles = Seq.empty,
        includeSchemas = Set("public"),
        basePackage = "graviton.pg.generated",
      )

      val generatedE = CodeGenerator.generate(
        jdbcUrl = postgres.getJdbcUrl("postgres", "postgres"),
        username = Some("postgres"),
        password = Some("postgres"),
        config = config,
      )

      val generated = generatedE match
        case Left(err) => fail(err.message)
        case Right(value) => value

      val expected = java.nio.file.Path.of("modules/pg/src/main/scala/graviton/pg/generated/public/schema.scala")
      assert(generated.nonEmpty, "no files were generated")
      val rendered = generated.head

      val generatedSource = Files.readString(rendered).trim
      val expectedSource  = Files.readString(expected).trim

      assertEquals(generatedSource, expectedSource)
    } finally {
      postgres.close()
    }
  }
}
