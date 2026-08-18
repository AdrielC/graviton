package dbcodegen

import java.sql.JDBCType

import munit.FunSuite

final class SchemaConverterSuite extends FunSuite {

  test("localTypeNameToSqlType resolves Postgres aliases") {
    assertEquals(SchemaConverter.localTypeNameToSqlType("bytea"), Some(JDBCType.BINARY))
    assertEquals(SchemaConverter.localTypeNameToSqlType("uuid"), Some(JDBCType.LONGVARCHAR))
    assertEquals(SchemaConverter.localTypeNameToSqlType("timestamptz"), Some(JDBCType.TIMESTAMP_WITH_TIMEZONE))
  }

  test("localTypeNameToSqlType detects arrays") {
    assertEquals(SchemaConverter.localTypeNameToSqlType("_uuid"), Some(JDBCType.ARRAY))
    assertEquals(SchemaConverter.localTypeNameToSqlType("_int8"), Some(JDBCType.ARRAY))
  }

  test("sqlToScalaType maps numeric types") {
    val scalaType = SchemaConverter.sqlToScalaType(JDBCType.BIGINT)
    assert(scalaType.exists(_.runtimeClass eq classOf[Long]))
  }
}
