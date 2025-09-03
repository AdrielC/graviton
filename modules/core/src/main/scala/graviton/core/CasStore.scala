package graviton.core

import zio.*
import zio.stream.*

trait CasStore extends KeyedStore:
  def insert: ZSink[Any, Throwable, Byte, Byte, BinaryKey.CasKey]
  def findBinary(key: BinaryKey.CasKey): IO[Throwable, Option[Bytes]]
  def readAttributes(
      key: BinaryKey.CasKey
  ): IO[Throwable, Option[BinaryAttributes]]
