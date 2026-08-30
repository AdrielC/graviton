package graviton.backend.rocks

import graviton.runtime.kv.KeyValueStore
import graviton.runtime.stores.StoreError
import zio.ZLayer

import java.nio.file.Path

object RocksLayers:
  def live(path: Path): ZLayer[Any, StoreError, KeyValueStore] =
    RocksKeyValueStore.layer(path)
