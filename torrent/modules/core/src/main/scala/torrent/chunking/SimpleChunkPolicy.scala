package torrent
package chunking

import torrent.{ Bytes, Length }

import zio.*
import zio.stream.*

/**
 * Simple chunking policies for basic functionality
 */
object SimpleChunkPolicy:

  /**
   * Fixed-size chunking
   */
  def fixedSize(chunkSize: Length): ZPipeline[Any, Nothing, Byte, Bytes] =
    ZPipeline
      .rechunk[Byte](chunkSize.toInt)
      .chunks
      .filter(_.nonEmpty)
      .map(Bytes.applyUnsafe(_))

  /**
   * Default chunking with 64KB chunks
   */
  val default: ZPipeline[Any, Nothing, Byte, Bytes] =
    fixedSize(`64KB`.toLength)
