package graviton.shared

import zio.blocks.schema.Schema
import zio.blocks.schema.json.{JsonCodec, JsonCodecDeriver, JsonSchema}

/**
 * ZIO Blocks JSON contract for a public API value.
 *
 * The representation may differ from `A`. That indirection is intentional:
 * ZIO Blocks 0.0.51 predates the merged opaque-wrapper layout fix in upstream
 * PR #1578, so Iron values are validated at the wire conversion instead of
 * being handed to the affected primitive-wrapper derivation path.
 */
trait ApiJsonCodec[A]:
  def encode(value: A): String
  def decode(value: String): Either[String, A]
  def jsonSchema: JsonSchema

object ApiJsonCodec:

  private val WireDeriver =
    JsonCodecDeriver
      .withTransientEmptyCollection(false)
      .withRequireCollectionFields(true)
      .withTransientDefaultValue(false)
      .withRequireDefaultValueFields(true)

  def derived[A](using schema: Schema[A]): ApiJsonCodec[A] =
    mapped[A, A](identity)(Right(_))

  def mapped[A, Wire](toWire: A => Wire)(fromWire: Wire => Either[String, A])(using schema: Schema[Wire]): ApiJsonCodec[A] =
    new ApiJsonCodec[A]:
      private val codec: JsonCodec[Wire] = schema.deriving(WireDeriver).derive

      override def encode(value: A): String =
        codec.encodeToString(toWire(value))

      override def decode(value: String): Either[String, A] =
        codec.decode(value).left.map(_.message).flatMap(fromWire)

      override def jsonSchema: JsonSchema =
        codec.toJsonSchema

end ApiJsonCodec

/**
 * One schema-derived JSON boundary shared by the JVM server, JVM SDK, and
 * Scala.js client.
 *
 * ZIO Blocks caches derivation per schema and format, so callers get one
 * contract without maintaining a parallel encoder on each platform.
 */
object ApiJson:

  def encode[A](value: A)(using codec: ApiJsonCodec[A]): String =
    codec.encode(value)

  def decode[A](value: String)(using codec: ApiJsonCodec[A]): Either[String, A] =
    codec.decode(value)

  def jsonSchema[A](using codec: ApiJsonCodec[A]): JsonSchema =
    codec.jsonSchema

end ApiJson
