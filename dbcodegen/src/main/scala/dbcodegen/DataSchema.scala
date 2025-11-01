package dbcodegen

import schemacrawler.schema.{Column, Index, Schema, Table, View}

case class DataColumn(
  name: String,
  scalaType: String,
  db: Column,
  pgType: Option[PgTypeResolver.ColumnInfo],
  checkConstraints: Seq[DataCheckConstraint] = Seq.empty,
  domain: Option[String] = None,
) {
  def scalaName = NameFormat.sanitizeScalaName(NameFormat.toCamelCase(name))
  def domainScalaName = domain.map(NameFormat.sanitizeScalaName).map(NameFormat.toPascalCase)
}

case class DataIndex(
  name: String,
  columns: Seq[DataColumn],
  db: Index,
) {
  def scalaName = NameFormat.sanitizeScalaName(NameFormat.toPascalCase(name))
}

case class DataTable(
  name: String,
  columns: Seq[DataColumn],
  indices: Seq[DataIndex],
  db: Table,
  checkConstraints: Seq[DataCheckConstraint] = Seq.empty,
) {
  def isView: Boolean = db.isInstanceOf[View]
  def scalaName       = NameFormat.sanitizeScalaName(NameFormat.toPascalCase(name))
}

case class DataEnumValue(
  name: String
) {
  def scalaName = NameFormat.sanitizeScalaName(name)
}

case class DataEnum(
  name: String,
  values: Seq[DataEnumValue],
) {
  def scalaName = NameFormat.sanitizeScalaName(NameFormat.toPascalCase(name))
}

case class DataSchema(
  name: String,
  tables: Seq[DataTable],
  enums: Seq[DataEnum],
  db: Schema,
  domains: Seq[String] = Seq.empty,
) {
  def scalaName = NameFormat.sanitizeScalaName(NameFormat.toCamelCase(name))
}

enum CheckScope:
  case Column, Table, Domain

case class DataCheckConstraint(
  name: String,
  expression: String,
  columns: Seq[String],
  scope: CheckScope,
)
