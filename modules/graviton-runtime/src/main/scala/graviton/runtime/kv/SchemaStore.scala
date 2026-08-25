package graviton.runtime.kv

import zio.ZIO

final case class SchemaStore(kv: KeyValueStore):
  def putSchema(name: KvKey, bytes: KvValue): ZIO[Any, Throwable, Unit] = kv.put(name, bytes)
  def getSchema(name: KvKey): ZIO[Any, Throwable, Option[KvValue]]      = kv.get(name)
