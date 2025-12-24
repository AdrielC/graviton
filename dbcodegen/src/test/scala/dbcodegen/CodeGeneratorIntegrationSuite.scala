package dbcodegen

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import munit.FunSuite

import java.io.File
import java.nio.file.Files
import scala.meta.*

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

      // The codegen uses scala.meta `syntax` + scalafmt, so textual output can legitimately shift
      // (imports ordering, whitespace, etc.). Compare the parsed tree instead to ensure semantic stability.
      val obtainedAst = dialects.Scala3(generatedSource).parse[Source] match
        case Parsed.Success(tree) => tree
        case Parsed.Error(pos, msg, _) =>
          fail(s"Failed to parse generated source at ${pos.startLine}:${pos.startColumn}: $msg\n$generatedSource")

      val expectedAst = dialects.Scala3(expectedSource).parse[Source] match
        case Parsed.Success(tree) => tree
        case Parsed.Error(pos, msg, _) =>
          fail(s"Failed to parse expected snapshot at ${pos.startLine}:${pos.startColumn}: $msg\n$expectedSource")

      assertEquals(obtainedAst.structure, expectedAst.structure)
    } finally {
      postgres.close()
    }
  }
}
