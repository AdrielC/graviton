package graviton
package core

import zio.*
import zio.stream.*
import graviton.core.model.Size

trait CasStore extends KeyedStore:
  def insert: ZSink[Any, Throwable, Byte, Byte, FileKey.CasKey.FileKey]
  def findBinary(key: FileKey.CasKey[? <: Size]): IO[Throwable, Option[Bytes]]
  def readAttributes(
    key: FileKey.CasKey[? <: Size],
  ): IO[Throwable, Option[BinaryAttributes]]
