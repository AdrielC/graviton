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

import cats.Monoid

import graviton.core.BinaryAttributeKey.foldNameRuntime

sealed trait BinaryAttributeKey[A](
  final val id: Name,
  final val prefix: Name,
  final val _schema: Schema[? <: A],
) extends Dynamic {
  self =>
  type ValueType <: A
  type SchemaType = Schema[ValueType]

  type Prefix = prefix.type
  given Prefix: ValueOf[Prefix] = ValueOf(prefix)

  type Nm = id.type
  given Nm: ValueOf[Nm] = ValueOf(id)

  type FN = BinaryAttributeKey.AddNameSpace[Prefix, Nm]

  type FullName = FN

  def fullName: FN = foldNameRuntime[Prefix, Nm](prefix, id)

  def schema: Schema[A] = _schema.asInstanceOf[Schema[A]]

  def default: Option[BinaryAttribute[A]] =
    val name = fullName
    schema.defaultValue.toOption.map(BinaryAttribute[A](_, name))

  inline def defaultValue[AA >: A: Monoid]: Option[AA] =
    default
      .map(_.value)
      .orElse(Some(Monoid.empty[AA]))

  def withDefault(value: A): BinaryAttributeKey[A] =
    new BinaryAttributeKey[A](id, prefix, schema.default(value)) {
      override final type ValueType  = A
      override final type SchemaType = Schema[ValueType]
    }

  def toAttribute[AA >: A: Monoid](value: Option[AA]): BinaryAttribute[AA] =
    BinaryAttribute(
      value
        .orElse(defaultValue[AA])
        .getOrElse(Monoid.empty[AA]),
      foldNameRuntime(fullName, "default"),
    )

  def coerce[B](using s: Schema[B]): Either[String, BinaryAttributeKey.Aux[B, Nm, Prefix]] =
    schema
      .coerce(s)
      .map(s =>
        BinaryAttributeKey.apply[B, Nm, Prefix](
          Nm.value,
          Prefix.value,
        )(using s)
      )
}
object BinaryAttributeKey:

  private def empty: Name = "attr"

  given [NS <: Name: ValueOf, N <: Name: ValueOf] => ValueOf[AddNameSpace[NS, N]] =
    new ValueOf[AddNameSpace[NS, N]](
      foldNameRuntime[NS, N](valueOf[NS], valueOf[N])
    )

  def foldNameRuntime[NS <: Name, N <: Name](
    ns: NS,
    n: N,
  ): Name & AddNameSpace[NS, N] =
    ns match
      case NoPrefix   =>
        n match
          case NoPrefix     => empty.asInstanceOf[Name & AddNameSpace[NS, N]]
          case nonEmptyName => nonEmptyName.asInstanceOf[Name & AddNameSpace[NS, N]]
      case nonEmptyNs =>
        n match
          case NoPrefix               => empty.asInstanceOf[Name & AddNameSpace[NS, N]]
          case n if n.startsWith(":") => (ns + n).asInstanceOf[Name & AddNameSpace[NS, N]]
          case n                      => (ns + ":" + n).asInstanceOf[Name & AddNameSpace[NS, N]]

  inline transparent def foldName[NS <: Name, N <: Name]: Name & AddNameSpace[NS, N] =
    inline compiletime.constValue[NS] match
      case NoPrefix   =>
        inline compiletime.constValue[N] match
          case NoPrefix     => "attr".asInstanceOf["attr" & Name & AddNameSpace[NS, N]]
          case nonEmptyName => valueOf[NS].asInstanceOf[NS & Name & AddNameSpace[NS, N]]
      case nonEmptyNs =>
        inline compiletime.constValue[N] match
          case NoPrefix               => "attr".asInstanceOf["attr" & Name & AddNameSpace[NS, N]]
          case n if n.startsWith(":") => valueOf[NS + N].asInstanceOf[(NS + N) & Name & AddNameSpace[NS, N]]
          case n                      => valueOf[NS + ":" + N].asInstanceOf[(NS + ":" + N) & Name & AddNameSpace[NS, N]]
  end foldName

  final type AddNameSpace[NS <: Name, N <: Name] <: Name =
    N match
      case NoPrefix =>
        Length[NS] match
          case 0 => "attr"
          case _ => NS
      case Name     =>
        Length[N] match
          case 0 => NS
          case 1 =>
            N match
              case ":" => NS + N
              case _   => NS + ":" + N
          case _ => NS + ":" + N
  end AddNameSpace
  object AddNameSpace:

    def apply[NS <: Name: ValueOf, N <: Name: ValueOf]: AddNameSpace[NS, N] =
      foldNameRuntime[NS, N](valueOf[NS], valueOf[N])

  final type NoPrefix = "" & Singleton & Name
  final val NoPrefix: NoPrefix = ""

  def apply[A, N <: Name & Singleton](_id: N)(using s: Schema[A]): Aux[A, N, NoPrefix] =
    (new BinaryAttributeKey[A](_id, NoPrefix, s) {
      override final type ValueType  = A
      override final type SchemaType = Schema[ValueType]
      override type FN               = AddNameSpace[Prefix, Nm]
      override type FullName         = FN
    }).asInstanceOf[Aux[A, N, NoPrefix]]

  def apply[A, N <: Name, P <: Name](
    _id: N,
    _prefix: P,
  )(using s: Schema[A]): Aux[A, N, P] =
    (new BinaryAttributeKey[A](_id, _prefix, s) {
      override final type ValueType  = A
      override final type SchemaType = Schema[ValueType]
      // override type Prefix = P
      override type FN               = AddNameSpace[Prefix, Nm]
      override final type FullName   = FN
      // override type Nm = N
    }).asInstanceOf[Aux[A, N, P]]

  final type Aux[A, K <: Name, P <: Name] = BinaryAttributeKey[A] {
    type ValueType  = A
    type SchemaType = Schema[A]
    type Nm         = K
    type Prefix     = P
    type FN         = AddNameSpace[Prefix, Nm]
    type FullName   = FN
  }

  type BinaryAttributeKeySchema = Long :: String :: String :: Instant :: UUID :: DynamicValue :: End

  given metaSchema: Schema[Schema[?]] =
    ExtensibleMetaSchema
      .fromSchema[MetaSchema, BinaryAttributeKeySchema](
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
        (a, id) =>
          new BinaryAttributeKey[Any](id.asInstanceOf[a.id.type], a.prefix, a.schema) {
            override final type ValueType = Any
            type FN                       = AddNameSpace[Prefix, Nm]
            type FullName                 = FN
          },
      ),
      Schema.Field[BinaryAttributeKey[? <: Any], String](
        "prefix",
        Schema[String],
        Chunk.empty[Any],
        Validation.minLength(1),
        r => r.prefix,
        (a, prefix) =>
          new BinaryAttributeKey[Any](a.id, prefix.asInstanceOf[a.prefix.type], a.schema.asInstanceOf[Schema[? <: Any]]) {
            override final type ValueType = Any
            type FN                       = AddNameSpace[Prefix, Nm]
            type FullName                 = FN
          },
      ),
      Schema.Field[BinaryAttributeKey[? <: Any], Schema[? >: Nothing <: Any]](
        "schema",
        metaSchema.asInstanceOf[Schema[Schema[?]]],
        Chunk.empty[Any],
        Validation.succeed,
        a => a.schema.asInstanceOf[Schema[?]],
        (a, schema) => BinaryAttributeKey[a.ValueType, a.id.type](a.id)(using schema.asInstanceOf[Schema[a.ValueType]]),
      ),
      (id, prefix, schema) => BinaryAttributeKey(id, prefix)(using schema),
      Chunk.empty[Any],
    )

  final type ConfirmedKeys[T <: Name, P <: Name] <: Boolean = P match
    case Name => Matches[T, "confirmed*"] || Matches[P, "attr:server"]
  end ConfirmedKeys

  final type AdvertisedKeys[T <: Name, P <: Name] <: Boolean = P match
    case Name => Matches[T, "advertised*"] || Matches[P, "attr:client"]
  end AdvertisedKeys

  transparent trait NamespacedKey[NameSpace <: Name & Singleton](
    using NameSpace: ValueOf[NameSpace]
  ) extends Dynamic:

    type NS = NameSpace

    type ToPrefix[P <: (NoPrefix | Name)] = P match
      case NoPrefix => NameSpace
      case Name     => AddNameSpace[NameSpace, P]

    def applyDynamic[V, N <: Name, P <: (NoPrefix | Name)](schema: Schema[V])(
      using N: ValueOf[N],
      P: ValueOf[ToPrefix[P]],
    ): Aux[V, N, ToPrefix[P]] =
      BinaryAttributeKey.apply[V, N, ToPrefix[P]](
        N.value,
        P.value,
      )(using schema)

    def selectDynamic[V: Schema](name: Name): Aux[V, name.type, NameSpace] =
      BinaryAttributeKey.apply[V, name.type, NameSpace](
        valueOf[name.type],
        valueOf[NameSpace],
      )(using Schema[V])

    final val fileName: Aux[String, "fileName", NameSpace]       = selectDynamic[String]("fileName")
    final val contentType: Aux[String, "contentType", NameSpace] = selectDynamic[String]("contentType")
    final val forks: Aux[Int, "forks", NameSpace]                = selectDynamic[Int]("forks")
    final val version: Aux[String, "version", NameSpace]         = selectDynamic[String]("version")
    final val build: Aux[String, "build", NameSpace]             = selectDynamic[String]("build")
    final val buildFp: Aux[String, "buildFp", NameSpace]         = selectDynamic[String]("buildFp")
    final val buildH: Aux[String, "buildH", NameSpace]           = selectDynamic[String]("buildH")

  end NamespacedKey

  object Server extends NamespacedKey["attr:server"]
  object Client extends NamespacedKey["attr:client"]

end BinaryAttributeKey
