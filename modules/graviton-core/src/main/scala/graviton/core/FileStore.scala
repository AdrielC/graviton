package graviton.core

import zio.*
import zio.stream.*

/**
 * High-level file-oriented store. Creates a sink that accepts a stream of bytes
 * and yields a content-addressed `FileKey` together with a `BlockManifest` that
 * describes how the underlying blocks compose the file.
 */
trait FileStore extends KeyedStore:
  def ingest: ZSink[Any, Throwable, Bytes, Nothing, (FileKey, BlockManifest)]
  def findBinary(key: FileKey): IO[Throwable, Option[Bytes]]
