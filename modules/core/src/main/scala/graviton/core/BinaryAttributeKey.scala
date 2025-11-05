package graviton.core

import zio.schema.validation.Validation
import zio.*
import zio.schema.*
import java.util.UUID
import java.time.Instant
import zio.schema.meta.MetaSchema
import zio.schema.meta.ExtensibleMetaSchema
import zio.constraintless.TypeList.{End, ::}
import graviton.core.model.{FileSize, BlockSize}
import scala.compiletime.ops.string.{Substring, +, Matches}
import scala.compiletime.ops.any.==
import scala.language.dynamics
import java.time.OffsetDateTime
import graviton.HashAlgorithm
import graviton.domain.HashBytes
import zio.prelude.NonEmptyMap

abstract case class BinaryAttributeKey[+N <: String & Singleton](id: N, _schema: Schema[?]) {
  type ValueType
  type SchemaType = Schema[ValueType]

  def schema: SchemaType = _schema.asInstanceOf[SchemaType]

  def coerce[A](using s: Schema[A]): Either[String, BinaryAttributeKey.Aux[A, N]] = 
    schema.coerce(s).map(s => BinaryAttributeKey[A, N](id.asInstanceOf[N])(using s))
}
object BinaryAttributeKey:

  def apply[A, N <: String & Singleton](id: N)(using s: Schema[A]): Aux[A, N] = new BinaryAttributeKey(id, s) { override type ValueType = A; override type Name = N }

  transparent inline def apply[A, N <: String & Singleton](using s: Schema[A]): Aux[A, N] = new BinaryAttributeKey(compiletime.constValue[N], s) { override type ValueType = A; override type Name = N }

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
      Schema.Field[BinaryAttributeKey[? <: String & Singleton], String]("id", Schema[String], Chunk.empty[Any], 
      Validation.minLength(1), r => r.id, (a, id) => new BinaryAttributeKey[a.id.type](id.asInstanceOf[a.id.type], a.schema) { override type ValueType = a.ValueType }),
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
    (Matches[T, "^attr:server$"] == true) =:= true
  
  type AdvertisedKeys[T <: Name] = 
    (Matches[T, "^attr:client$"] == false) =:= true

  type AddNameSpace[NameSpace <: String & Singleton, N <: Name] <: Name =
    Substring[NameSpace, 0, 1] == ":" match
      case true => (NameSpace + N) & Name
      case false => (NameSpace + ":" + N) & Name
  
  
  transparent trait NamespacedKey[NameSpace <: Name] extends Dynamic:
    self =>
    type Name = NameSpace
    opaque type Aux[A, K <: Name] <:
      BinaryAttributeKey.Aux[A, AddNameSpace[Name, K]] & Singleton =
         BinaryAttributeKey.Aux[A, AddNameSpace[Name, K]]

    transparent inline def contentType: Aux[String, "contentType"] = selectDynamic[String]("contentType")
    transparent inline def fileSize: Aux[FileSize, "fileSize"] = selectDynamic[FileSize]("fileSize")
    transparent inline def fileName: Aux[String, "fileName"] = selectDynamic[String]("fileName")
    transparent inline def fileHash: Aux[NonEmptyMap[HashAlgorithm, HashBytes], "fileHash"] = selectDynamic[NonEmptyMap[HashAlgorithm, HashBytes]]("fileHash")
    transparent inline def forks: Aux[Int, "forks"] = selectDynamic[Int]("forks")
    transparent inline def createdAt: Aux[OffsetDateTime, "createdAt"] = selectDynamic[OffsetDateTime]("createdAt")
    transparent inline def ownerId: Aux[UUID, "ownerId"] = selectDynamic[UUID]("ownerId")

    inline def selectDynamic[V: Schema](name: String)
    : Aux[V, name.type] =
      BinaryAttributeKey[V, AddNameSpace[NameSpace, name.type]]


  type Server = NamespacedKey["attr:server"]
  object Server extends NamespacedKey["attr:server"]:
    
  end Server

  type Client = NamespacedKey["attr:client"]
  object Client extends NamespacedKey["attr:client"]:
  
  end Client

  export Server.{Aux as ServerAux, *}
  
