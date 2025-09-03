package graviton

import zio.*
import zio.stream.*

trait FileStore:
  def put(
      meta: FileMetadata,
      blockSize: Int
  ): ZSink[Any, Throwable, Byte, Nothing, FileKey]
  def get(key: FileKey): IO[Throwable, Option[Bytes]]
  def describe(key: FileKey): IO[Throwable, Option[FileDescriptor]]
  def list(selector: FileKeySelector): ZStream[Any, Throwable, FileDescriptor]
