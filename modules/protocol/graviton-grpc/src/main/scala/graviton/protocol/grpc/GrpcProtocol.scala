package graviton.protocol.grpc

import com.google.protobuf.ByteString
import graviton.core.keys.{BinaryKey, KeyBits}
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.all.*
import zio.Chunk

object GrpcProtocol:
  val MaxChunkBytes: Int          = 1024 * 1024
  val MaxInboundMessageBytes: Int = 2 * 1024 * 1024

  type DataChunk = Chunk[Byte] :| MaxLength[1048576]

  object DataChunk:
    def fromByteString(value: ByteString): Either[String, DataChunk] =
      if value.size() > MaxChunkBytes then Left(s"gRPC data frame exceeds $MaxChunkBytes bytes")
      else Chunk.fromArray(value.toByteArray).refineEither[MaxLength[1048576]]

  def parseBlobKey(value: String): Either[String, BinaryKey.Blob] =
    KeyBits.parse(value).flatMap(BinaryKey.blob)

  def render(key: BinaryKey): String = key.bits.render
