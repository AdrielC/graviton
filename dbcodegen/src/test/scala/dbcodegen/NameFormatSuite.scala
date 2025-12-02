package dbcodegen

import munit.FunSuite

final class NameFormatSuite extends FunSuite {

  test("sanitizeScalaName escapes reserved words") {
    println(NameFormat.sanitizeScalaName("type"))
    assertEquals(NameFormat.sanitizeScalaName("type"), "`type`")
    assertEquals(NameFormat.sanitizeScalaName("given"), "`given`")
  }

  test("sanitizeScalaName leaves valid identifiers unchanged") {
    assertEquals(NameFormat.sanitizeScalaName("storeKey"), "storeKey")
    assertEquals(NameFormat.sanitizeScalaName("Store"), "Store")
  }

  test("toCamelCase handles underscores and whitespace") {
    assertEquals(NameFormat.toCamelCase("store_key"), "storeKey")
    assertEquals(NameFormat.toCamelCase("store key"), "storeKey")
    assertEquals(NameFormat.toCamelCase("STORE-Key"), "storeKey")
  }

  test("toPascalCase capitalises correctly") {
    assertEquals(NameFormat.toPascalCase("store_key"), "StoreKey")
    assertEquals(NameFormat.toPascalCase("store key"), "StoreKey")
    assertEquals(NameFormat.toPascalCase("STORE-Key"), "StoreKey")
  }
}
