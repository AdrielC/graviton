package graviton.core

import zio.schema.validation.Validation
import zio.*
import zio.schema.*
import java.util.UUID
import java.time.Instant
import zio.schema.meta.MetaSchema
import zio.schema.meta.ExtensibleMetaSchema
import zio.constraintless.TypeList.{End, ::}
import scala.compiletime.ops.string.{Matches, Length, +}
import scala.compiletime.ops.boolean.||
import scala.language.dynamics
import scala.annotation.publicInBinary


import cats.Monoid

import BinaryAttributeKey.foldName

sealed trait BinaryAttributeKey[A] @publicInBinary private[graviton] (
  final val id: Name, 
  final val prefix: Name, 
  final val _schema: Schema[? <: A]
) {
  self =>
  type ValueType <: A
  type SchemaType = Schema[ValueType]

  type Prefix = prefix.type
  given Prefix: ValueOf[Prefix] = ValueOf(prefix)

  type Nm = id.type
  given Nm: ValueOf[Nm] = ValueOf(id)

  type FN = BinaryAttributeKey.AddNameSpace[Prefix, Nm]

  type FullName = FN
  
  inline given FullName: ValueOf[FullName] = 
    ValueOf(foldName[Prefix, Nm](prefix, id))

  inline def fullName: FullName = FullName.value

  def schema: Schema[A] = _schema.asInstanceOf[Schema[A]]

  inline def default: Option[BinaryAttribute[A]] = 
    val nm = foldName(fullName, "default")
    schema.defaultValue.toOption.map(BinaryAttribute[A](_, nm))

  inline def defaultValue[AA >: A: Monoid]: Option[AA] = 
    default.map(_.value)
    .orElse(Some(Monoid.empty[AA]))

  def withDefault(value: A): BinaryAttributeKey[A] = 
    new BinaryAttributeKey[A](id, prefix, schema.default(value)) {
      override final type ValueType = A
      override final type SchemaType = Schema[ValueType]
    }
  
  inline def toAttribute[AA >: A: Monoid](value: Option[AA]): BinaryAttribute[AA] = 
    BinaryAttribute(value.orElse(defaultValue[AA])
    .getOrElse(
      Monoid.empty[AA]), 
    foldName(fullName, "default"))

  def coerce[B](using s: Schema[B]): Either[String, BinaryAttributeKey.Aux[B, Nm, Prefix]] = 
    schema.coerce(s).map(s => 
      BinaryAttributeKey.apply[B, Nm, Prefix](
        Nm.value, Prefix.value
      )(using s)
    )
}
object BinaryAttributeKey:


  inline transparent def foldName[NS <: Name, N <: Name](ns: NS, n: N): AddNameSpace[NS, N] = 
    inline compiletime.erasedValue[N] match
      case _: Nothing => ns.asInstanceOf[AddNameSpace[NS, N]]
      case _ => 
        inline ns.length match
          case 0 => n.length match
            case 0 => "attr".asInstanceOf["attr" & AddNameSpace[NS, N]]
            case _ => 
              inline val name = ns + ":" + n
              name.asInstanceOf[name.type & AddNameSpace[NS, N]]
          case _ => n.length match
            case 0 => ns.asInstanceOf[NS & AddNameSpace[NS, N]]
            case _ => 
              inline val name = ns + ":" + n
              name.asInstanceOf[name.type & AddNameSpace[NS, N]]
        end match 
  end foldName

  final type AddNameSpace[NS <: Name, N <: Name] <: ((Name) | "attr" | NS | N) =
    N match 
      case Nothing => NS
      case _ => Length[NS] match
          case 0 => Length[N] match
            case 0 => "attr"
            case _ => (NS + ":" + N)
          case _ => Length[N] match
            case 0 => NS
            case _ => (NS + ":" + N)
  end AddNameSpace


  final type NoPrefix = "" & Singleton & Name
  given NoPrefix: ValueOf[NoPrefix] = ValueOf("")


  def apply[A, N <: Name & Singleton](_id: N)(using s: Schema[A]): Aux[A, N, NoPrefix] = 
    (new BinaryAttributeKey[A](_id, NoPrefix.value, s) {
      override final type ValueType = A
      override final type SchemaType = Schema[ValueType]
      override type FN = AddNameSpace[Prefix, Nm]
      override type FullName = FN
    }).asInstanceOf[Aux[A, N, NoPrefix]]

  def apply[A, N <: Name, P <: Name](
    _id: N, 
    _prefix: P
    )(using s: Schema[A]): Aux[A, N, P] = 
      (new BinaryAttributeKey[A](_id, _prefix, s) {
        override final type ValueType = A
        override final type SchemaType = Schema[ValueType]
        // override type Prefix = P
        override type FN = AddNameSpace[Prefix, Nm]
        override final type FullName = FN
        // override type Nm = N
      }).asInstanceOf[Aux[A, N, P]]


  final type Aux[A, K <: Name, P <: Name] = BinaryAttributeKey[A] { 
    type ValueType = A
    type SchemaType = Schema[A]
    type Nm = K
    type Prefix = P
    type FN = AddNameSpace[Prefix, Nm]
    type FullName = FN
  }

  type BinaryAttributeKeySchema = Long :: String :: String :: Instant :: UUID :: DynamicValue :: End

  given metaSchema: Schema[Schema[?]] =
    ExtensibleMetaSchema.fromSchema[MetaSchema, BinaryAttributeKeySchema](
      MetaSchema.schema
    )
    .toSchema
    .asInstanceOf[Schema[Schema[?]]]
  
  given binaryAttributeKeySchema: Schema[BinaryAttributeKey[?]] =
    Schema.CaseClass3[Name, Name, Schema[?], BinaryAttributeKey[? <: Any]](
      TypeId.parse("graviton.core.BinaryAttributeKey"),
      Schema.Field[BinaryAttributeKey[? <: Any], String](
        "id", 
        Schema[String], 
        Chunk.empty[Any], 
        Validation.minLength(1), 
        r => r.id,
        (a, id) => new BinaryAttributeKey[Any](id.asInstanceOf[a.id.type], a.prefix, a.schema) { 
          override final type ValueType = Any
          type FN = AddNameSpace[Prefix, Nm]
          type FullName = FN
      }),
      Schema.Field[BinaryAttributeKey[? <: Any], String](
        "prefix", 
        Schema[String], 
        Chunk.empty[Any], 
        Validation.minLength(1), 
        r => r.prefix,
        (a, prefix) => new BinaryAttributeKey[Any](a.id, prefix.asInstanceOf[a.prefix.type], 
        a.schema.asInstanceOf[Schema[? <: Any]]) { 
          override final type ValueType = Any
          type FN = AddNameSpace[Prefix, Nm]
          type FullName = FN
        }),
      Schema.Field[BinaryAttributeKey[? <: Any], Schema[? >: Nothing <: Any]](
        "schema", 
        metaSchema.asInstanceOf[Schema[Schema[?]]],
      Chunk.empty[Any],
      Validation.succeed,
      a => a.schema.asInstanceOf[Schema[?]],
      (a, schema) => BinaryAttributeKey[a.ValueType, a.id.type](a.id)(using schema.asInstanceOf[Schema[a.ValueType]]),
      ),
      (id, prefix, schema) => BinaryAttributeKey(id, prefix
      )(using schema),
      Chunk.empty[Any],
    )
    
  final type ConfirmedKeys[T <: Name, P <: Name] <: Boolean = P match
    case Name => Matches[T, "confirmed*"] || Matches[P, "attr:server"]
  end ConfirmedKeys
  
  final type AdvertisedKeys[T <: Name, P <: Name] <: Boolean = P match
    case Name => Matches[T, "advertised*"] || Matches[P, "attr:client"]
  end AdvertisedKeys


  inline given [NS <: Name, N <: Name] => (
    NS: ValueOf[NS], N: ValueOf[N]) => ValueOf[AddNameSpace[NS, N]] = 
      new ValueOf[AddNameSpace[NS, N]]((
        foldName[NS, N](NS.value, N.value)
      ))

  transparent trait NamespacedKey[NameSpace <: Name & Singleton](
    using NameSpace: ValueOf[NameSpace]
  ) extends Dynamic:

    type NS = NameSpace

    type ToPrefix[P <: (NoPrefix | Name)] = P match
      case NoPrefix => NameSpace
      case Name => AddNameSpace[NameSpace, P]

    transparent inline def applyDynamic[
      V, N <: Name & Singleton, P <: (NoPrefix | Name)](schema: Schema[V])
    : Aux[V, N, ToPrefix[P]] =
      BinaryAttributeKey.apply[V, N, ToPrefix[P]](
        valueOf[N], valueOf[ToPrefix[P]]
      )(using schema)
    end applyDynamic


    def selectDynamic[V: Schema](name: Name): Aux[V, name.type, NameSpace] =
      (new BinaryAttributeKey[V](
        name, 
      NameSpace.value, 
      summon[Schema[V]]
      ) {
        override final type ValueType = V
        override final type SchemaType = Schema[V]
        override type FN = AddNameSpace[Prefix, Nm]
        override type FullName = FN
      }).asInstanceOf[Aux[V, name.type, NameSpace]]
    end selectDynamic

    final inline transparent def fileName = selectDynamic[String]("fileName")
    final inline transparent def contentType = selectDynamic[ String]("contentType")
    final inline transparent def forks = selectDynamic[Int]("forks")
    final inline transparent def version = selectDynamic[String]("version")
    final inline transparent def build = selectDynamic[String]("build")
    final inline transparent def buildFp = selectDynamic[String]("buildFp")
    final inline transparent def buildH = selectDynamic[String]("buildH")
    final inline transparent def buildImplId = selectDynamic[String]("buildImplId")

  end NamespacedKey


  object Server extends NamespacedKey["attr:server"]
  object Client extends NamespacedKey["attr:client"]

end BinaryAttributeKey


