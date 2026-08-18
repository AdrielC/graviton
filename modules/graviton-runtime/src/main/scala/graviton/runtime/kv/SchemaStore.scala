package graviton.runtime.kv

import zio.ZIO

final case class SchemaStore(kv: KeyValueStore):
  def putSchema(name: String, bytes: Array[Byte]): ZIO[Any, Throwable, Unit] = kv.put(name, bytes)
  def getSchema(name: String): ZIO[Any, Throwable, Option[Array[Byte]]]      = kv.get(name)
