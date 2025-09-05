package graviton.core

import zio.*
import zio.stream.*

trait BlobStore extends BlockStore:
  def insertWith(
    key: BinaryKey.WritableKey
  ): ZSink[Any, Throwable, Byte, Byte, Boolean]
  def findBinary(key: BinaryKey): IO[Throwable, Option[Bytes]]
  def copy(from: BinaryKey, to: BinaryKey.WritableKey): IO[Throwable, Unit]
  override def delete(key: BinaryKey): IO[Throwable, Boolean]
  def writeWithAttrs(
    provided: BinaryAttributes
  ): ZSink[Any, Throwable, Byte, Byte, (BinaryKey.CasKey, BinaryAttributes)]
