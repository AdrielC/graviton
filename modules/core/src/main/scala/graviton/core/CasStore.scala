package graviton.core

import zio.*
import zio.stream.*

trait CasStore extends KeyedStore:
  def insert: ZSink[Any, Throwable, Byte, Byte, FileKey.CasKey]
  def findBinary(key: FileKey.CasKey): IO[Throwable, Option[Bytes]]
  def readAttributes(
    key: FileKey.CasKey
  ): IO[Throwable, Option[BinaryAttributes]]
