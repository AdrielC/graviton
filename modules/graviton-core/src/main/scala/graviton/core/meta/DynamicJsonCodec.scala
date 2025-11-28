package graviton.core.meta

import zio.json.EncoderOps
import zio.json.ast.Json
import zio.schema.{DynamicValue, Schema}
import zio.schema.codec.JsonCodec

object DynamicJsonCodec:

  /** Encode a DynamicValue for a *known* schema as JSON. */
  def encodeDynamic[A](schema: Schema[A], dyn: DynamicValue): Either[String, Json] =
    for
      typed     <- schema.fromDynamic(dyn)
      jsonString = JsonCodec.jsonCodec(schema).encodeJson(typed, None).toString
      json      <- Json.decoder.decodeJson(jsonString).left.map(_.toString)
    yield json

  /** Decode JSON into a DynamicValue.Record for a *known* schema. */
  def decodeDynamicRecord[A](schema: Schema[A], json: Json): Either[String, DynamicValue.Record] =
    for
      value  <- JsonCodec.jsonCodec(schema).decodeJson(json.toJson).left.map(_.toString)
      record <- schema.toDynamic(value) match
                  case rec: DynamicValue.Record => Right(rec)
                  case other                    => Left(s"Expected DynamicValue.Record but received $other")
    yield record
