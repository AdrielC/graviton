package graviton.core

import zio.schema.*
import zio.schema.codec.*
import zio.schema.DynamicValue
import scala.collection.immutable.ListMap

final case class BinaryAttributes private (
    values: ListMap[BinaryAttributeKey[?], DynamicValue]
):
  def get[A](k: BinaryAttributeKey[A])(using Schema[A]): Option[A] =
    values.get(k).flatMap { dv =>
      dv.toTypedValue(using Schema[A]).toOption
    }

  def put[A](k: BinaryAttributeKey[A], a: A)(using
      Schema[A]
  ): BinaryAttributes =
    copy(values =
      values.updated(k, DynamicValue.fromSchemaAndValue(Schema[A], a))
    )

  def ++(other: BinaryAttributes): BinaryAttributes =
    copy(values = values ++ other.values)

object BinaryAttributes:
  val empty: BinaryAttributes = BinaryAttributes(ListMap.empty)

  def of[A](k: BinaryAttributeKey[A], a: A)(using Schema[A]): BinaryAttributes =
    empty.put(k, a)
