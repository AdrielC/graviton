package graviton

import graviton.core.BinaryAttributes
import zio.*
import zio.stream.*

trait FileStore:
  def put(
      attrs: BinaryAttributes,
      blockSize: Int
  ): ZSink[Any, Throwable, Byte, Nothing, FileKey]
  def get(key: FileKey): IO[Throwable, Option[Bytes]]
  def describe(key: FileKey): IO[Throwable, Option[FileDescriptor]]
  def list(selector: FileKeySelector): ZStream[Any, Throwable, FileDescriptor]
  def delete(key: FileKey): IO[Throwable, Boolean]
