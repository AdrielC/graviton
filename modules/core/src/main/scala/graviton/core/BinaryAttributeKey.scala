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
import scala.compiletime.ops.string.{Substring, +, Matches}
import scala.compiletime.ops.any.==
import scala.language.dynamics
import java.time.OffsetDateTime
import graviton.HashAlgorithm
import graviton.domain.HashBytes
import zio.prelude.NonEmptyMap
import scala.annotation.publicInBinary


abstract case class BinaryAttributeKey[+N <: String & Singleton] @publicInBinary private[graviton] (id: N, _schema: Schema[?]) {
  type ValueType
  type SchemaType = Schema[ValueType]

  def schema: SchemaType = _schema.asInstanceOf[SchemaType]

  def coerce[A](using s: Schema[A]): Either[String, BinaryAttributeKey.Aux[A, N]] = 
    schema.coerce(s).map(s => BinaryAttributeKey[A, N](id.asInstanceOf[N])(using s))
}
object BinaryAttributeKey:

  def apply[A, N <: Name](id: N)(using s: Schema[A]): Aux[A, N] = 
    new BinaryAttributeKey[N](id, s) {
      type ValueType = A
    }

  inline def apply[A, N <: Name: ValueOf](s: Schema[A]): Aux[A, N] = 
    apply[A, N](valueOf[N])(using s)

  type Aux[A, K <: Name] = BinaryAttributeKey[K] { type ValueType = A; type SchemaType = Schema[A] }

  type BinaryAttributeKeySchema = Long :: String :: String :: Instant :: UUID :: DynamicValue :: End

  given metaSchema: Schema[Schema[?]] =
    ExtensibleMetaSchema.fromSchema[MetaSchema, BinaryAttributeKeySchema](
      MetaSchema.schema
    )
    .toSchema
    .asInstanceOf[Schema[Schema[?]]]
  
  given binaryAttributeKeySchema: Schema[BinaryAttributeKey[? <: String & Singleton]] =
    Schema.CaseClass2[String, Schema[?], BinaryAttributeKey[? <: String & Singleton]](
      TypeId.parse("graviton.core.BinaryAttributeKey"),
      Schema.Field[BinaryAttributeKey[? <: Name], String]("id", Schema[String], Chunk.empty[Any], 
      Validation.minLength(1), r => r.id, (a, id) => new BinaryAttributeKey[a.id.type](id.asInstanceOf[a.id.type], a.schema) { 
        override type ValueType = a.ValueType 
        }),
      Schema.Field[BinaryAttributeKey[? <: String & Singleton], Schema[? >: Nothing <: Any]](
        "schema", 
        metaSchema.asInstanceOf[Schema[Schema[?]]],
      Chunk.empty[Any],
      Validation.succeed,
      a => a.schema.asInstanceOf[Schema[?]],
      (a, schema) => BinaryAttributeKey[a.ValueType, a.id.type](a.id)(using schema.asInstanceOf[Schema[a.ValueType]]),
      ),
      (id, schema) => BinaryAttributeKey(id)(using schema),
      Chunk.empty[Any],
    )
    

  type ConfirmedKeys[T <: Name] = 
    (Matches[T, "^attr:server(?:$|:.*)"] == true) =:= true
  
  type AdvertisedKeys[T <: Name] = 
    (Matches[T, "^attr:client(?:$|:.*)"] == true) =:= true

  final type AddNameSpace[NameSpace <: Name, N <: Name] <: Name =
    Substring[NameSpace, 0, 1] match
      case ":" => (NameSpace + N) & Name
      case _ => (NameSpace + ":" + N) & Name
  
  
  transparent trait NamespacedKey[NameSpace <: Name] extends Dynamic:
    self =>
    type NS = NameSpace
    opaque type Aux[A, K <: Name] <:
      BinaryAttributeKey.Aux[A, AddNameSpace[NS, K]] =
         BinaryAttributeKey.Aux[A, AddNameSpace[NS, K]]

    inline def contentType: Aux[String, "contentType"] = selectDynamic[String]("contentType")
    inline def fileSize: Aux[FileSize, "fileSize"] = selectDynamic[FileSize]("fileSize")
    inline def fileName: Aux[String, "fileName"] = selectDynamic[String]("fileName")
    inline def fileHash: Aux[NonEmptyMap[HashAlgorithm, HashBytes], "fileHash"] = selectDynamic[NonEmptyMap[HashAlgorithm, HashBytes]]("fileHash")
    inline def forks: Aux[Int, "forks"] = selectDynamic[Int]("forks")
    inline def createdAt: Aux[OffsetDateTime, "createdAt"] = selectDynamic[OffsetDateTime]("createdAt")
    inline def ownerId: Aux[UUID, "ownerId"] = selectDynamic[UUID]("ownerId")

    inline def selectDynamic[V](name: String)
    : Aux[V, name.type] =
      given ValueOf[name.type] = new ValueOf[name.type](name)
      BinaryAttributeKey.apply[V, AddNameSpace[NS, name.type]](
        compiletime.summonInline[Schema[V]]
      )
    end selectDynamic
  end NamespacedKey

  object Server extends NamespacedKey["attr:server"]:
  end Server
  
  object Client extends NamespacedKey["attr:client"]:
  end Client
