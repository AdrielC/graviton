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
import scala.language.dynamics
import scala.annotation.publicInBinary
import cats.implicits.*

import cats.Monoid

import BinaryAttributeKey.AddNameSpace
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
  
  given FullName: ValueOf[FullName] = 
    ValueOf(foldName[Prefix, Nm](prefix, id))

  def fullName: FullName = FullName.value

  def schema: Schema[A] = _schema.asInstanceOf[Schema[A]]

  inline def default: Option[BinaryAttribute[A]] = 
    val nm = foldName(fullName, "default")
    schema.defaultValue.toOption.map(BinaryAttribute[A](_, nm))

  def defaultValue[AA >: A: Monoid]: Option[AA] = 
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
      BinaryAttributeKey.apply[B, Nm, Prefix]
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


  final type NoPrefix = "" & Singleton
  given NoPrefix: ValueOf[NoPrefix] = ValueOf("")


  def apply[A, N <: Name](_id: N)(using s: Schema[A]): Aux[A, N, NoPrefix] = 
    new BinaryAttributeKey[A](_id, NoPrefix.value, s) {
      override final type ValueType = A
      override final type SchemaType = Schema[ValueType]
      override type Prefix = NoPrefix
      override type Nm = N
      override type FN = AddNameSpace[Prefix, Nm]
      override type FullName = FN
    }

  def apply[A, N <: Name: ValueOf, P <: Name: ValueOf](
    _id: N, 
    _prefix: P
    )(using s: Schema[A]): Aux[A, N, P] = 
      new BinaryAttributeKey[A](_id, _prefix, s) {
        override final type ValueType = A
        override final type SchemaType = Schema[ValueType]

        override type FN = AddNameSpace[Prefix, Nm]
        override final type FullName = FN

        override type Prefix = P
        override type Nm = N
        
      }

  final inline def apply[A, N <: Name: ValueOf](s: Schema[A]): Aux[A, N, NoPrefix] {
    type ValueType = A
    type SchemaType = Schema[ValueType]
    type Nm = N
    type Prefix = NoPrefix
    type FN = AddNameSpace[Prefix, Nm]
    type FullName = FN
  } = apply[A, N, NoPrefix](using summon[ValueOf[N]], NoPrefix, s)

  final inline def apply[A, N <: Name: ValueOf, P <: Name: ValueOf](using s: Schema[A]): Aux[A, N, P] {
    type ValueType = A
    type SchemaType = Schema[ValueType]
    type Nm = N
    type Prefix = P
    type FN = AddNameSpace[Prefix, Nm]
    type FullName = FN
  } = apply[A, N, P](valueOf[N], valueOf[P])(using summon[ValueOf[N]], summon[ValueOf[P]], s)

  final type Aux[A, K <: Name, P <: Name] = BinaryAttributeKey[A] { 
    type ValueType = A
    type SchemaType = Schema[ValueType]
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
          inline given FullName: ValueOf[FullName] = 
            ValueOf(foldName[Prefix, Nm](Prefix.value, Nm.value))
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
      )(using ValueOf(id), ValueOf(prefix), schema),
      Chunk.empty[Any],
    )
    
  final type ConfirmedKeys[T <: Name, P <: Name] <: Boolean = P match
    case Name => Matches[T, "server*"] | Matches[P, "server*"]
  end ConfirmedKeys
  
  final type AdvertisedKeys[T <: Name, P <: Name] <: Boolean = P match
    case Name => Matches[T, "client*"] | Matches[P, "client*"]
  end AdvertisedKeys


  inline given [NS <: Name: ValueOf, N <: Name: ValueOf] => (
    NS: ValueOf[NS], N: ValueOf[N]) => ValueOf[AddNameSpace[NS, N]] = 
      new ValueOf[AddNameSpace[NS, N]]((
        foldName[NS, N](NS.value, N.value)
      ))

  transparent trait NamespacedKey[NameSpace <: Name & Singleton: ValueOf] extends Dynamic:


    type ToPrefix[P <: Name & Singleton | NoPrefix | AddNameSpace[?, ?]] = P match
      case NoPrefix => NameSpace
      case AddNameSpace[n, p] => AddNameSpace[n, p]
      case Name => AddNameSpace[NameSpace, P]

    final type Aux[A, K <: Name, P <: (Name | Nothing | NoPrefix | AddNameSpace[?, ?])] = 

        BinaryAttributeKey.Aux[A, K, ToPrefix[P]] {
          type ValueType = A
          type SchemaType = Schema[A]
          type Nm = K
          type FN = AddNameSpace[Prefix, Nm]
          type FullName = FN
        }

    transparent inline def applyDynamic[
      V, N <: Name: ValueOf, P <: Name: ValueOf](schema: Schema[V])
    : Aux[V, N, P] =
      BinaryAttributeKey.apply[V, N, ToPrefix[P]](
        using 
        summon[ValueOf[N]], 
        ValueOf(compiletime.constValue[ToPrefix[P]]), 
        schema
      )
    end applyDynamic


    transparent inline def selectDynamic[V: Schema](name: Name): Aux[V, name.type, NoPrefix] =
      given ValueOf[name.type] = ValueOf(name)
      new BinaryAttributeKey[V, name.type, ToPrefix[NoPrefix]](
        using ValueOf(name),
        ValueOf(compiletime.constValue[ToPrefix[NoPrefix]]),
        summon[Schema[V]]
      ) {
        override final type ValueType = V
        override final type SchemaType = Schema[ValueType]
        override type Prefix = NameSpace
        override type Nm = name.type
        override type FN = AddNameSpace[Prefix, Nm]
        override type FullName = FN
      }
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


