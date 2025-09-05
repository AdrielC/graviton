package torrent.serialization

import zio.Chunk
import zio.schema.Schema
import zio.schema.codec.{ JsonCodec, ProtobufCodec }

trait Serializer:
  def serialize[A](value: A)(using schema: Schema[A]): Chunk[Byte]

object Serializer:

  implicit object JsonSerializer extends Serializer {
    override def serialize[A](value: A)(using schema: Schema[A]): Chunk[Byte] =
      JsonCodec.schemaBasedBinaryCodec[A].encode(value)
  }

  val protobuf: Serializer = new Serializer {
    override def serialize[A](value: A)(using schema: Schema[A]): Chunk[Byte] =
      ProtobufCodec.Encoder.process(schema, value)
  }

end Serializer
