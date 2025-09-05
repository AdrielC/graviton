package torrent

import zio.schema.{ Schema, derived }

/**
 * Result of inserting a chunk
 */
enum InsertChunkResult derives Schema:
  /** Chunk successfully inserted */
  case Success

  /** All chunks now available, file is complete */
  case Complete

  /** Maximum chunk size exceeded */
  case ChunkTooLarge(maxSize: Long)

  /** Invalid chunk definition */
  case InvalidChunkDef(message: String)
