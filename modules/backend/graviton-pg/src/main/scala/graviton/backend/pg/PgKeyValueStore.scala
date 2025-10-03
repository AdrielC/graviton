package graviton.backend.pg

import graviton.runtime.kv.KeyValueStore
import zio.ZIO

final class PgKeyValueStore extends KeyValueStore:
  override def put(key: String, value: Array[Byte]): ZIO[Any, Throwable, Unit] = ZIO.unit
  override def get(key: String): ZIO[Any, Throwable, Option[Array[Byte]]]      = ZIO.succeed(None)
  override def delete(key: String): ZIO[Any, Throwable, Unit]                  = ZIO.unit
