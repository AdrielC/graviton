package graviton.runtime.stores

import graviton.core.keys.BinaryKey
import zio.stream.ZStream
import zio.ZIO

trait BlockStore:
  def put(key: BinaryKey, bytes: ZStream[Any, Throwable, Byte]): ZIO[Any, Throwable, Unit]
  def get(key: BinaryKey): ZStream[Any, Throwable, Byte]
  def exists(key: BinaryKey): ZIO[Any, Throwable, Boolean]
