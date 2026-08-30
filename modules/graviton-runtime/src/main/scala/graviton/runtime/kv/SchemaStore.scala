package graviton.runtime.kv

import graviton.runtime.stores.StoreError
import zio.IO

final case class SchemaStore(kv: KeyValueStore):
  def putSchema(name: KvKey, bytes: KvValue): IO[StoreError, Unit] = kv.put(name, bytes)
  def getSchema(name: KvKey): IO[StoreError, Option[KvValue]]      = kv.get(name)
