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


final case class BinaryAttribute[+A](value: A, source: String):
  def map[B](f: A => B): BinaryAttribute[B] = BinaryAttribute(f(value), source)
  def withSource(s: String): BinaryAttribute[A] = copy(source = s + (if (s == source) then "" else ":update"))
  def withNewValue[B](v: B)(using ev: B =!= (? >: A)): BinaryAttribute[B] = copy(value = v, source = source + ":update")
  def withValue[AA >: A](v: AA): BinaryAttribute[AA] = copy(value = v, source = source + (if (v == value) then "" else ":update"))
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


  def from[A](attr: BinaryAttribute[A])(using Schema[A]): DynamicAttr =
    DynamicAttr(
      DynamicValue.fromSchemaAndValue(Schema[A], attr.value),
      attr.source,
    )

final case class BinaryAttributes private[graviton] (
  advertised: Map[BinaryAttributeKey[String & Singleton], DynamicAttr],
  confirmed: Map[BinaryAttributeKey[String & Singleton], DynamicAttr],
):
  self =>
  
  def getAdvertised[A, K <: Name: AdvertisedKeys](k: BinaryAttributeKey.Aux[A, K]): Option[BinaryAttribute[A]] =
    advertised.get(k).flatMap(_.to[A](using k.schema))
    .orElse(k.schema.defaultValue.toOption.map(BinaryAttribute[A](_, "default")))

  def getConfirmed[A, K <: Name: ConfirmedKeys](k: BinaryAttributeKey.Aux[A, K]): Option[BinaryAttribute[A]] =
    confirmed.get(k).flatMap(_.to[A](using k.schema))
    .orElse(k.schema.defaultValue.toOption.map(BinaryAttribute[A](_, "default")))

  def putAdvertised[A, K <: Name: AdvertisedKeys](k: BinaryAttributeKey.Aux[A, K], attr: BinaryAttribute[A])(using Schema[A]): BinaryAttributes =
    self.copy(advertised = advertised.updated(k, DynamicAttr.from(attr)))

  def putConfirmed[A, K <: Name: ConfirmedKeys](k: BinaryAttributeKey.Aux[A, K], attr: BinaryAttribute[A])(using Schema[A]): BinaryAttributes =
    self.copy(confirmed = confirmed.updated(k, DynamicAttr.from(attr)))

  def updateAdvertised[A: Schema, K <: Name: AdvertisedKeys](
    k: BinaryAttributeKey.Aux[A, K]
    )(f: BinaryAttribute[A] => BinaryAttribute[A],
    default: Option[A] = None,
    ): BinaryAttributes =
    getAdvertised[A, K](k).map { a => 
      val v = f(a)
      self.putAdvertised[A, K](k, v)
    }.getOrElse(default.fold(self){ a => 
      given Schema[A] = k.schema
      val id = BinaryAttributeKey.Client.default[A].id
      self.putAdvertised[A, K](k, BinaryAttribute(a, id))
     })

  def updateConfirmed[A: Schema, K <: Name: ConfirmedKeys](
    k: BinaryAttributeKey.Aux[A, K]
  )(
    f: BinaryAttribute[A] => BinaryAttribute[A],
    default: Option[A] = None,
  ): BinaryAttributes =
    getConfirmed[A, K](k).map { a => 
      val v = f(a)
      self.putConfirmed[A, K](k, v)
    }.getOrElse(default.fold(self){ a => 
      given Schema[A] = k.schema
      val id = BinaryAttributeKey.Server.default[A].id
      self.putConfirmed[A, K](k, BinaryAttribute(a, id))
     })

  def ++(other: BinaryAttributes): BinaryAttributes =
    self.copy(
      advertised = advertised |+| other.advertised,
      confirmed = confirmed |+| other.confirmed,
    )

object BinaryAttributes:
  val empty: BinaryAttributes = BinaryAttributes(ListMap.empty, ListMap.empty)

  def advertised[A, K <: Name: AdvertisedKeys](k: BinaryAttributeKey.Aux[A, K], value: A, source: String)(using Schema[A]): BinaryAttributes =
    empty.putAdvertised(k, BinaryAttribute(value, source))

  def confirmed[A, K <: Name: ConfirmedKeys](k: BinaryAttributeKey.Aux[A, K], value: A, source: String)(using Schema[A]): BinaryAttributes =
    empty.putConfirmed(k, BinaryAttribute(value, source))

  private final val FilenamePattern  = "^[^\\/\\r\\n]+$".r
  private final val MediaTypePattern = "^[\\w.+-]+/[\\w.+-]+$".r

  private[graviton] object fiber:

    val current: FiberRef[BinaryAttributes] = 
      Unsafe.unsafely:
        FiberRef.unsafe.make(
          empty,
          a => a.updateConfirmed(BinaryAttributeKey.Server.forks
          )(
            _.map(_ + 1), Some(0)),
          (a, b) => a ++ b,
        )
      

    def withCurrent(attrs: BinaryAttributes): RIO[Scope, Unit] =
      fiber.current.locally(attrs)(ZIO.unit)

  def validate(attrs: BinaryAttributes): IO[GravitonError, Unit] =
    def validateAll(
      values: List[String],
      pattern: scala.util.matching.Regex,
      msg: String,
    ): IO[GravitonError, Unit] =
      ZIO.foreachDiscard(values) { v =>
        if pattern.pattern.matcher(v).matches() then ZIO.unit
        else ZIO.fail(GravitonError.PolicyViolation(msg))
      }

    val filenames = List(
      attrs.getAdvertised(BinaryAttributeKey.Client.fileName).map(_.value),
      attrs.getConfirmed(BinaryAttributeKey.Server.fileName).map(_.value),
    ).flatten

    val mediaTypes = List(
      attrs.getAdvertised(BinaryAttributeKey.Client.contentType).map(_.value),
      attrs.getConfirmed(BinaryAttributeKey.Server.contentType).map(_.value),
    ).flatten

    for
      _ <- validateAll(filenames, FilenamePattern, "invalid filename")
      _ <- validateAll(mediaTypes, MediaTypePattern, "invalid media type")
    yield ()
