package torrent
package serialization

import torrent.Bytes

import zio.Chunk
import zio.schema.Schema
import zio.schema.codec.{ JsonCodec, ProtobufCodec }

trait Deserializer {
  def deserialize[A](value: Chunk[Byte])(using schema: Schema[A]): Either[String, A]
}

object Deserializer {

  implicit object JsonDeserializer extends Deserializer {
    override def deserialize[A](value: Chunk[Byte])(using schema: Schema[A]): Either[String, A] =
      Bytes.either(value).flatMap(b => JsonCodec.jsonCodec(schema).decoder.decodeJson(b.asBase64String))
  }

  object ProtobufDeserializer extends Deserializer {
    override def deserialize[A](value: Chunk[Byte])(using schema: Schema[A]): Either[String, A] =
      ProtobufCodec.Decoder(value).decode(schema).left.map(_.message)
  }
}
