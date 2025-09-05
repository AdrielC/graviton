package torrent.serialization

import zio.schema.{ DynamicValue, Schema }

object DynamicValueHelpers:

  def of[A: Schema](value: A): DynamicValue =
    DynamicValue.fromSchemaAndValue(Schema[A], value)

  def ofOption[A: Schema](value: Option[A]): DynamicValue =
    value.map(of[A]).getOrElse(DynamicValue.NoneValue)

  def tuple(value: DynamicValue, value2: DynamicValue, values: DynamicValue*): DynamicValue =
    (value +: value2 +: values).toList match
      case (left :: last :: Nil)         => DynamicValue.Tuple(left, last)
      case (left :: right :: rr :: rest) => tuple(DynamicValue.Tuple(left, right), rr, rest*)
      case _                             => sys.error("DynamicValueHelpers.tuple requires at least two parameters")

end DynamicValueHelpers
