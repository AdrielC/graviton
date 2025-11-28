package graviton.core.meta

import zio.schema.{DynamicValue, Schema}

final case class SchemaMigration[A, B](convert: A => Either[SchemaMigrationError, B]):
  def apply(value: A): Either[SchemaMigrationError, B] = convert(value)

sealed trait SchemaMigrationError extends Product with Serializable:
  def message: String

object SchemaMigrationError:
  final case class Message(message: String) extends SchemaMigrationError

type AnySchemaDef = SchemaDef[?, ? <: String]

final case class SchemaDef[Meta, SV <: String](
  namespace: NamespaceUrn,
  versionRepr: SemVerRepr,
  version: SemVer,
  id: SchemaId,
  schema: Schema[Meta],
  migrateFrom: AnySchemaDef => Either[SchemaMigrationError, SchemaMigration[?, Meta]],
)

object SchemaDef:
  def toDynamicRecord[A](schema: Schema[A], value: A): Either[String, DynamicValue.Record] =
    schema.toDynamic(value) match
      case record: DynamicValue.Record => Right(record)
      case other                       => Left(s"Expected DynamicValue.Record but received $other")

  def fromDynamicRecord[A](schema: Schema[A], record: DynamicValue.Record): Either[String, A] =
    schema.fromDynamic(record)
