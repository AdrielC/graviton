package graviton.core

import zio.schema.validation.Validation
import zio.*
import zio.schema.*
import java.util.UUID
import java.time.Instant
import zio.schema.meta.MetaSchema
import zio.schema.meta.ExtensibleMetaSchema
import zio.constraintless.TypeList.{End, ::}
import graviton.core.model.FileSize

abstract case class BinaryAttributeKey(id: String, _schema: Schema[?]) {
  type ValueType
  type SchemaType = Schema[ValueType]

  def schema: SchemaType = _schema.asInstanceOf[SchemaType]

  def coerce[A](using s: Schema[A]): Either[String, BinaryAttributeKey] = 
    schema.coerce(s).map(s => BinaryAttributeKey(id)(using s.asInstanceOf[Schema[ValueType]]))
}
object BinaryAttributeKey:

  def apply[A](id: String)(using s: Schema[A]): Aux[A] = new BinaryAttributeKey(id, s) { override type ValueType = A }

  type Aux[A] = BinaryAttributeKey { type ValueType = A; type SchemaType = Schema[A] }

  type BinaryAttributeKeySchema = Long :: String :: String :: Instant :: UUID :: DynamicValue :: End

  given metaSchema: Schema[Schema[?]] =
    ExtensibleMetaSchema.fromSchema[MetaSchema, BinaryAttributeKeySchema](
      MetaSchema.schema
    )
    .toSchema
    .asInstanceOf[Schema[Schema[?]]]
  
  given binaryAttributeKeySchema: Schema[BinaryAttributeKey] =
    Schema.CaseClass2[String, Schema[?], BinaryAttributeKey](
      TypeId.parse("graviton.core.BinaryAttributeKey"),
      Schema.Field[BinaryAttributeKey, String]("id", Schema[String], Chunk.empty[Any], 
      Validation.minLength(1), r => r.id, (a, id) => new BinaryAttributeKey(id, a.schema) { override type ValueType = a.ValueType }),
      Schema.Field[BinaryAttributeKey, Schema[? >: Nothing <: Any]](
        "schema", 
        metaSchema.asInstanceOf[Schema[Schema[?]]],
      Chunk.empty[Any],
      Validation.succeed,
      a => a.schema.asInstanceOf[Schema[?]],
      (a, schema) => BinaryAttributeKey(a.id)(using schema.asInstanceOf[Schema[a.ValueType]]),
      ),
      (id, schema) => BinaryAttributeKey(id)(using schema),
      Chunk.empty[Any],
    )
    


  val size: BinaryAttributeKey.Aux[FileSize] =
    BinaryAttributeKey[FileSize]("attr:size")

  val filename: BinaryAttributeKey.Aux[String] =
    BinaryAttributeKey[String]("attr:filename")

  val contentType: BinaryAttributeKey.Aux[String] =
    BinaryAttributeKey[String]("attr:contentType")

  val createdAt: BinaryAttributeKey.Aux[Instant] =
    BinaryAttributeKey[Instant]("attr:createdAt")

  val ownerOrgId: BinaryAttributeKey.Aux[UUID] =
    BinaryAttributeKey[UUID]("attr:ownerOrgId")
