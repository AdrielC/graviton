package graviton.core

import graviton.GravitonError
import zio.*
import zio.schema.*
import zio.schema.DynamicValue
import scala.collection.immutable.ListMap
// import scala.compiletime.ops.string
import BinaryAttributeKey.{ConfirmedKeys, AdvertisedKeys}
import cats.Monoid
import cats.implicits.*
import zio.json.*

import BinaryAttributeKey.{Server, Client}


final case class BinaryAttribute[+A](value: A, source: String):
  def map[B](f: A => B): BinaryAttribute[B] = BinaryAttribute(f(value), source)
  def withSource(s: String): BinaryAttribute[A] = copy(source = s + (if (s == source) then "" else ":update"))
  def withNewValue[B](v: B)(using ev: B =!= (? >: A)): BinaryAttribute[B] = copy(value = v, source = source + ":update")
  def withValue[AA >: A](v: AA): BinaryAttribute[AA] = copy(value = v, source = source + (if (v == value) then "" else ":update"))

  def id: String = source
end BinaryAttribute

private final case class DynamicAttr(value: DynamicValue, source: String):
  def to[A](using Schema[A]): Option[BinaryAttribute[A]] =
    value.toTypedValue(using Schema[A]).toOption.map(BinaryAttribute(_, source))

private object DynamicAttr:
  given zio.json.JsonEncoder[DynamicValue] = 
    zio.schema.codec.JsonCodec.jsonEncoder(DynamicValue.schema)
  given Monoid[DynamicAttr] = new Monoid[DynamicAttr]:
    def empty: DynamicAttr = DynamicAttr(DynamicValue.NoneValue, "unknown")
    def combine(x: DynamicAttr, y: DynamicAttr): DynamicAttr = 
      DynamicAttr((x.value.toJsonAST, y.value.toJsonAST)
      .mapN(_ merge _).as(x)
      .getOrElse(y).value, x.source)


  def from[A: Schema](attr: BinaryAttribute[A]): DynamicAttr =
    DynamicAttr(
      DynamicValue.fromSchemaAndValue(summon[Schema[A]], attr.value),
      attr.source,
    )

final case class BinaryAttributes private[graviton] (
  advertised: Map[BinaryAttributeKey[?], DynamicAttr],
  confirmed: Map[BinaryAttributeKey[?], DynamicAttr],
):
  self =>
  
  transparent inline def getAdvertised[A, K <: Name, P <: Name](
    k: BinaryAttributeKey.Aux[A, K, P])(
    using AdvertisedKeys[K, P] =:= true
    ): Option[BinaryAttribute[A]] =
    advertised.get(k).flatMap(_.to[A](using k.schema))
    .orElse(k.schema.defaultValue.toOption.map(BinaryAttribute[A](_, "default")))

  transparent inline def getConfirmed[A, K <: Name, P <: Name](
    k: BinaryAttributeKey.Aux[A, K, P])(
    using ConfirmedKeys[K, P] =:= true): Option[BinaryAttribute[A]] =
    confirmed.get(k).flatMap(_.to[A](using k.schema))
    .orElse(k.schema.defaultValue.toOption.map(BinaryAttribute[A](_, "default")))

  transparent inline def putAdvertised[A, K <: Name, P <: Name](
    k: BinaryAttributeKey.Aux[A, K, P], 
    attr: BinaryAttribute[A]
  )(using AdvertisedKeys[K, P] =:= true): BinaryAttributes =
    self.copy(advertised = advertised.updated(k, DynamicAttr.from(attr)(using k.schema)))

  transparent inline def putConfirmed[A, K <: Name, P <: Name](
    k: BinaryAttributeKey.Aux[A, K, P], 
    attr: BinaryAttribute[A]
  )(using ConfirmedKeys[K, P] =:= true): BinaryAttributes =
    self.copy(confirmed = confirmed.updated(k, DynamicAttr.from(attr)(using k.schema)))

  inline def updateAdvertised[A, K <: Name, P <: Name](
    k: BinaryAttributeKey.Aux[A, K, P]
    )(
      f: BinaryAttribute[A] => BinaryAttribute[A],
      default: Option[A] = None,
    )(using AdvertisedKeys[K, P] =:= true): BinaryAttributes =
    getAdvertised(k).map { a => 
      val v = f(a)
      putAdvertised(k, v)
    }.getOrElse(default.fold(self){ a => 
      k.default.map(_.source).fold(self){ defId => 
        putAdvertised(k, BinaryAttribute(a, defId))
      }
    })

  inline def updateConfirmed[A, K <: Name, P <: Name](
    k: BinaryAttributeKey.Aux[A, K, P],
    f: BinaryAttribute[A] => BinaryAttribute[A],
    default: Option[A] = None,
  )(using (ConfirmedKeys[K, P] =:= true)): BinaryAttributes =
    given Monoid[A] = scala.compiletime.summonInline[Monoid[A]]
    getConfirmed(k).fold(
      default.fold(self){ a => 
        putConfirmed(k, k.withDefault(a).toAttribute(Some(a)))
      }
    ) { a => putConfirmed(k, f(a)) }

  inline def ++(other: BinaryAttributes): BinaryAttributes =
    self.copy(
      advertised = advertised |+| other.advertised,
      confirmed = confirmed |+| other.confirmed,
    )

object BinaryAttributes:
  val empty: BinaryAttributes = BinaryAttributes(ListMap.empty, ListMap.empty)

  def advertised[A, K <: Name, P <: Name](
    k: BinaryAttributeKey.Aux[A, K, P],
     value: A, 
     source: String
  )(using AdvertisedKeys[K, P] =:= true): BinaryAttributes =
    empty.putAdvertised(k, BinaryAttribute(value, source))

  def confirmed[A, K <: Name, P <: Name](
    k: BinaryAttributeKey.Aux[A, K, P], 
    value: A, 
    source: String
  )(using ConfirmedKeys[K, P] =:= true): BinaryAttributes =
    empty.putConfirmed(k, BinaryAttribute(value, source))

  private final val FilenamePattern  = "^[^\\/\\r\\n]+$".r
  private final val MediaTypePattern = "^[\\w.+-]+/[\\w.+-]+$".r

  private[graviton] object fiber:
    transparent inline def forks: BinaryAttributeKey.Aux[Int, "forks", "attr:server"] = 
      BinaryAttributeKey.Server.forks
    // transparent inline def name = forks.fullName

    inline def current: FiberRef[BinaryAttributes] = 
      Unsafe.unsafely:
        FiberRef.unsafe.make[BinaryAttributes](
          empty,
          (a: BinaryAttributes) => a.updateConfirmed(
              forks,
            _.map(_ + 1), 
            Some(0)),
          (a, b) => a ++ b
        )
    

  inline def withCurrent(attrs: BinaryAttributes): RIO[Scope, Unit] =
    fiber.current.locally(attrs)(ZIO.unit)

  final def validate(attrs: BinaryAttributes): IO[GravitonError, Unit] =
    def validateAll(
      values: List[String],
      pattern: scala.util.matching.Regex,
      msg: String,
    ): IO[GravitonError, Unit] =
      ZIO.foreachDiscard(values) { v =>
        if pattern.pattern.matcher(v).matches() then ZIO.unit
        else ZIO.fail(GravitonError.PolicyViolation(msg))
      }

    transparent inline def clientFilename = Client.fileName
    transparent inline def serverFilename = Server.fileName
    transparent inline def clientContentType = Client.contentType
    transparent inline def serverContentType = Server.contentType


    val filenames = List( 
      attrs.getAdvertised(clientFilename).map(_.value),
      attrs.getConfirmed(serverFilename).map(_.value),
    ).flatten

    val mediaTypes = List(
      attrs.getAdvertised(clientContentType).map(_.value),
      attrs.getConfirmed(serverContentType).map(_.value),
    ).flatten

    for
      _ <- validateAll(filenames, FilenamePattern, "invalid filename")
      _ <- validateAll(mediaTypes, MediaTypePattern, "invalid media type")
    yield ()
