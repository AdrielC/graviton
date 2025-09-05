package torrent

import scala.compiletime.ops.int.*

import io.github.iltotore.iron.constraint.all.LessEqual
import io.github.iltotore.iron.constraint.numeric.Greater
import io.github.iltotore.iron.{ DescribedAs, RefinedType }

import zio.schema.Schema
import zio.schema.annotation.description
import zio.stream.ZStream

opaque type ByteStream <: ZStream[Any, Throwable, Byte] = ZStream[Any, Throwable, Byte]

object ByteStream:

  def apply(stream: ZStream[Any, Throwable, Byte], chunkSize: Option[ChunkSize] = None): ByteStream =
    chunkSize.fold(stream)(stream.rechunk(_))

  def fromBytes(bytes: Bytes): ByteStream =
    ZStream.fromChunk(bytes)

  def empty: ByteStream = ZStream.empty

type ChunkSize = ChunkSize.T
object ChunkSize
    extends RefinedType[
      Int,
      DescribedAs[
        Greater[0] & LessEqual[1024 * 1024 * 10], // 10MB
        "Chunk size should be between 1 and 1024 * 1024 * 10 bytes. It determines the maximum size of a data chunk processed at a time."
      ]
    ]:
  inline val DEFAULT      = 1024 * 1024
  val default: ChunkSize  = apply(DEFAULT)
  given Schema[ChunkSize] = Schema[Int]
    .transformOrFail(either, Right(_))
    .annotate(description(rtc.message))
