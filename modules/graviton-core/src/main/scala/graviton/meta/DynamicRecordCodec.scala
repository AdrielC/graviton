package graviton.meta

import java.util.Base64
import scala.collection.immutable.ListMap

import zio.Chunk
import zio.json.ast.Json
import zio.schema.DynamicValue
import zio.schema.DynamicValue.Record
import zio.schema.{Schema, StandardType, TypeId}

/**
 * Helpers for converting strongly typed metadata into canonical [[DynamicValue.Record]] instances.
 */
object DynamicRecordCodec:

  def toRecord[A](schema: Schema[A], value: A): Either[String, Record] =
    schema.toDynamic(value) match
      case rec: Record => Right(rec)
      case other       => Left(s"Expected dynamic record, obtained: $other")

  def fromRecord[A](schema: Schema[A], record: Record): Either[String, A] =
    schema.fromDynamic(record).left.map(_.toString)

/**
 * Lossless conversions between [[DynamicValue.Record]] and `zio-json` ASTs. These helpers stay entirely inside the
 * schema/DynamicValue world so that Graviton can treat JSON, Protobuf, etc. as presentation formats.
 */
object DynamicJsonCodec:

  inline private def widen[A](typ: StandardType[A]): StandardType[Any] =
    typ.asInstanceOf[StandardType[Any]]

  def encodeDynamic(schema: Schema[?], dyn: DynamicValue): Either[String, Json] =
    schema
      .fromDynamic(dyn)
      .left
      .map(_.toString)
      .flatMap(value => dynamicToJson(schema.asInstanceOf[Schema[Any]].toDynamic(value.asInstanceOf[Any])))

  def decodeDynamicRecord[A](schema: Schema[A], json: Json): Either[String, Record] =
    for
      dynamic <- jsonToDynamic(json)
      value   <- schema.fromDynamic(dynamic).left.map(_.toString)
      record  <- schema.toDynamic(value) match
                   case rec: Record => Right(rec)
                   case other       => Left(s"Expected record, obtained: $other")
    yield record

  private def dynamicToJson(value: DynamicValue): Either[String, Json] =
    value match
      case Record(_, values) =>
        values
          .foldLeft[Either[String, List[(String, Json)]]](Right(List.empty)) { case (acc, (k, v)) =>
            for
              fields <- acc
              json   <- dynamicToJson(v)
            yield (k -> json) :: fields
          }
          .map(entries => Json.Obj(Chunk.fromIterable(entries.reverse)))

      case DynamicValue.Sequence(values) =>
        values
          .foldLeft[Either[String, List[Json]]](Right(List.empty)) { case (acc, value) =>
            for
              arr  <- acc
              json <- dynamicToJson(value)
            yield json :: arr
          }
          .map(values => Json.Arr(Chunk.fromIterable(values.reverse)))

      case DynamicValue.SetValue(values) =>
        values
          .foldLeft[Either[String, List[Json]]](Right(List.empty)) { case (acc, value) =>
            for
              arr  <- acc
              json <- dynamicToJson(value)
            yield json :: arr
          }
          .map(values => Json.Arr(Chunk.fromIterable(values.reverse)))

      case DynamicValue.Dictionary(entries) =>
        entries
          .foldLeft[Either[String, List[(String, Json)]]](Right(List.empty)) { case (acc, (keyDyn, valueDyn)) =>
            for
              fields <- acc
              key    <- primitiveToString(keyDyn)
              value  <- dynamicToJson(valueDyn)
            yield (key -> value) :: fields
          }
          .map(entries => Json.Obj(Chunk.fromIterable(entries.reverse)))

      case DynamicValue.Primitive(value, typ) =>
        primitiveToJson(value, typ)

      case DynamicValue.SomeValue(value) =>
        dynamicToJson(value)

      case DynamicValue.NoneValue =>
        Right(Json.Null)

      case DynamicValue.Tuple(left, right) =>
        for
          l <- dynamicToJson(left)
          r <- dynamicToJson(right)
        yield Json.Arr(l, r)

      case DynamicValue.LeftValue(value) =>
        dynamicToJson(value).map(json => Json.Obj("left" -> json))

      case DynamicValue.RightValue(value) =>
        dynamicToJson(value).map(json => Json.Obj("right" -> json))

      case DynamicValue.BothValue(left, right) =>
        for
          l <- dynamicToJson(left)
          r <- dynamicToJson(right)
        yield Json.Obj("left" -> l, "right" -> r)

      case DynamicValue.Error(message) =>
        Left(message)

      case other =>
        Left(s"Unsupported dynamic value for JSON encoding: $other")

  private def jsonToDynamic(json: Json): Either[String, DynamicValue] =
    json match
      case obj: Json.Obj =>
        obj.fields
          .foldLeft[Either[String, ListMap[String, DynamicValue]]](Right(ListMap.empty)) { case (acc, (k, v)) =>
            for
              map <- acc
              dyn <- jsonToDynamic(v)
            yield map + (k -> dyn)
          }
          .map(map => DynamicValue.Record(TypeId.Structural, map))

      case arr: Json.Arr =>
        arr.elements
          .foldLeft[Either[String, List[DynamicValue]]](Right(List.empty)) { case (acc, value) =>
            for
              list <- acc
              dyn  <- jsonToDynamic(value)
            yield dyn :: list
          }
          .map(values => DynamicValue.Sequence(Chunk.fromIterable(values.reverse)))

      case Json.Bool(value) =>
        Right(DynamicValue.Primitive(value, widen(StandardType.BoolType)))

      case Json.Str(value) =>
        Right(DynamicValue.Primitive(value, widen(StandardType.StringType)))

      case Json.Num(value) =>
        val decimal = value
        if decimal.scale == 0 then
          try
            val longVal = decimal.longValueExact()
            Right(DynamicValue.Primitive(longVal, widen(StandardType.LongType)))
          catch
            case _: ArithmeticException =>
              Right(DynamicValue.Primitive(decimal, widen(StandardType.BigDecimalType)))
        else Right(DynamicValue.Primitive(decimal, widen(StandardType.BigDecimalType)))

      case Json.Null =>
        Right(DynamicValue.NoneValue)

      case null =>
        Left("JSON node was null")

  private def primitiveToString(value: DynamicValue): Either[String, String] =
    value match
      case DynamicValue.Primitive(str: String, _) => Right(str)
      case other                                  => Left(s"Dictionary key must be a string, received $other")

  private def primitiveToJson(value: Any, typ: StandardType[?]): Either[String, Json] =
    typ match
      case StandardType.UnitType           => Right(Json.Null)
      case StandardType.StringType         => Right(Json.Str(value.asInstanceOf[String]))
      case StandardType.BoolType           => Right(Json.Bool(value.asInstanceOf[Boolean]))
      case StandardType.ShortType          => Right(Json.Num(BigDecimal(value.asInstanceOf[Short])))
      case StandardType.IntType            => Right(Json.Num(BigDecimal(value.asInstanceOf[Int])))
      case StandardType.LongType           => Right(Json.Num(BigDecimal(value.asInstanceOf[Long])))
      case StandardType.FloatType          => Right(Json.Num(BigDecimal.decimal(value.asInstanceOf[Float])))
      case StandardType.DoubleType         => Right(Json.Num(BigDecimal(value.asInstanceOf[Double])))
      case StandardType.BigDecimalType     => Right(Json.Num(value.asInstanceOf[java.math.BigDecimal]))
      case StandardType.BigIntegerType     => Right(Json.Num(new java.math.BigDecimal(value.asInstanceOf[java.math.BigInteger])))
      case StandardType.UUIDType           => Right(Json.Str(value.asInstanceOf[java.util.UUID].toString))
      case StandardType.BinaryType         =>
        Right(Json.Str(Base64.getEncoder.encodeToString(value.asInstanceOf[Chunk[Byte]].toArray)))
      case StandardType.CharType           => Right(Json.Str(value.asInstanceOf[Char].toString))
      case StandardType.LocalDateType      => Right(Json.Str(value.asInstanceOf[java.time.LocalDate].toString))
      case StandardType.LocalTimeType      => Right(Json.Str(value.asInstanceOf[java.time.LocalTime].toString))
      case StandardType.LocalDateTimeType  =>
        Right(Json.Str(value.asInstanceOf[java.time.LocalDateTime].toString))
      case StandardType.OffsetTimeType     =>
        Right(Json.Str(value.asInstanceOf[java.time.OffsetTime].toString))
      case StandardType.OffsetDateTimeType =>
        Right(Json.Str(value.asInstanceOf[java.time.OffsetDateTime].toString))
      case StandardType.ZonedDateTimeType  =>
        Right(Json.Str(value.asInstanceOf[java.time.ZonedDateTime].toString))
      case StandardType.InstantType        =>
        Right(Json.Str(value.asInstanceOf[java.time.Instant].toString))
      case StandardType.PeriodType         =>
        Right(Json.Str(value.asInstanceOf[java.time.Period].toString))
      case StandardType.DurationType       =>
        Right(Json.Str(value.asInstanceOf[java.time.Duration].toString))
      case other                           =>
        Left(s"Unable to encode primitive value $value of type $other to JSON")
