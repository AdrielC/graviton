package graviton.core

import zio.*
import zio.stream.*

trait BlobStore extends BlockStore:
  def insertWith(
    key: FileKey.WritableKey
  ): ZSink[Any, Throwable, Byte, Byte, Boolean]
  def findBinary(key: FileKey): IO[Throwable, Option[Bytes]]
  def copy(from: FileKey, to: FileKey.WritableKey): IO[Throwable, Unit]
  override def delete(key: FileKey): IO[Throwable, Boolean]
  def writeWithAttrs(
    provided: BinaryAttributes
  ): ZSink[Any, Throwable, Byte, Byte, (FileKey.CasKey, BinaryAttributes)]
