package graviton.core

import graviton.GravitonError
import zio.*
import zio.schema.*
import zio.schema.DynamicValue
import scala.collection.immutable.ListMap

final case class BinaryAttribute[A](value: A, source: String)

private final case class DynamicAttr(value: DynamicValue, source: String):
  def to[A](using Schema[A]): Option[BinaryAttribute[A]] =
    value.toTypedValue(using Schema[A]).toOption.map(BinaryAttribute(_, source))

private object DynamicAttr:
  def from[A](attr: BinaryAttribute[A])(using Schema[A]): DynamicAttr =
    DynamicAttr(
      DynamicValue.fromSchemaAndValue(Schema[A], attr.value),
      attr.source
    )

final case class BinaryAttributes private (
    advertised: ListMap[BinaryAttributeKey[?], DynamicAttr],
    confirmed: ListMap[BinaryAttributeKey[?], DynamicAttr]
):
  def getAdvertised[A](k: BinaryAttributeKey[A])(using
      Schema[A]
  ): Option[BinaryAttribute[A]] =
    advertised.get(k).flatMap(_.to[A])

  def getConfirmed[A](k: BinaryAttributeKey[A])(using
      Schema[A]
  ): Option[BinaryAttribute[A]] =
    confirmed.get(k).flatMap(_.to[A])

  def putAdvertised[A](k: BinaryAttributeKey[A], attr: BinaryAttribute[A])(using
      Schema[A]
  ): BinaryAttributes =
    copy(advertised = advertised.updated(k, DynamicAttr.from(attr)))

  def putConfirmed[A](k: BinaryAttributeKey[A], attr: BinaryAttribute[A])(using
      Schema[A]
  ): BinaryAttributes =
    copy(confirmed = confirmed.updated(k, DynamicAttr.from(attr)))

  def ++(other: BinaryAttributes): BinaryAttributes =
    BinaryAttributes(
      advertised ++ other.advertised,
      confirmed ++ other.confirmed
    )

object BinaryAttributes:
  val empty: BinaryAttributes = BinaryAttributes(ListMap.empty, ListMap.empty)

  def advertised[A](k: BinaryAttributeKey[A], value: A, source: String)(using
      Schema[A]
  ): BinaryAttributes =
    empty.putAdvertised(k, BinaryAttribute(value, source))

  def confirmed[A](k: BinaryAttributeKey[A], value: A, source: String)(using
      Schema[A]
  ): BinaryAttributes =
    empty.putConfirmed(k, BinaryAttribute(value, source))

  private val FilenamePattern = "^[^\\/\\r\\n]+$".r
  private val MediaTypePattern = "^[\\w.+-]+/[\\w.+-]+$".r

  def validate(attrs: BinaryAttributes): IO[GravitonError, Unit] =
    def validateAll(
        values: List[String],
        pattern: scala.util.matching.Regex,
        msg: String
    ): IO[GravitonError, Unit] =
      ZIO.foreachDiscard(values) { v =>
        if pattern.pattern.matcher(v).matches() then ZIO.unit
        else ZIO.fail(GravitonError.PolicyViolation(msg))
      }

    val filenames = List(
      attrs.getAdvertised(BinaryAttributeKey.filename).map(_.value),
      attrs.getConfirmed(BinaryAttributeKey.filename).map(_.value)
    ).flatten

    val mediaTypes = List(
      attrs.getAdvertised(BinaryAttributeKey.contentType).map(_.value),
      attrs.getConfirmed(BinaryAttributeKey.contentType).map(_.value)
    ).flatten

    for
      _ <- validateAll(filenames, FilenamePattern, "invalid filename")
      _ <- validateAll(mediaTypes, MediaTypePattern, "invalid media type")
    yield ()
