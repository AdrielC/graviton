package torrent

import zio.schema.{ DeriveSchema, Schema }

/**
 * Definition of a binary chunk
 *
 * @param index
 *   The 0-based index of this chunk
 * @param totalChunks
 *   Total number of chunks in the complete file
 * @param lastChunk
 *   Whether this is the last chunk
 */
enum ChunkDef:

  case Index(
    index:       Int,
    totalChunks: Int
  )

  case Offset(
    offset:    Long,
    length:    Long,
    sizeLimit: Option[Long] = None
  )

  def isLast: Boolean =
    this match
      case Index(index, totalChunks)     => totalChunks - 1 == index
      case Offset(offset, length, limit) => limit.exists(limit => offset + length >= limit)

object ChunkDef:
  /**
   * Create a chunk definition for a single chunk file
   */
  val single: ChunkDef = ChunkDef.Index(0, 1)

  /**
   * Create a chunk definition for a multi-chunk file
   */
  def apply(index: Int, totalChunks: Int): ChunkDef =
    ChunkDef.Index(index, totalChunks)

  implicit val schema: Schema[ChunkDef] = DeriveSchema.gen
