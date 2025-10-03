package graviton.runtime.kv

import zio.ZIO

trait KeyValueStore:
  def put(key: String, value: Array[Byte]): ZIO[Any, Throwable, Unit]
  def get(key: String): ZIO[Any, Throwable, Option[Array[Byte]]]
  def delete(key: String): ZIO[Any, Throwable, Unit]
