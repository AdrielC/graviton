package graviton.backend.rocks

import graviton.runtime.kv.KeyValueStore
import zio.ZLayer

import java.nio.file.Path

object RocksLayers:
  def live(path: Path): ZLayer[Any, Throwable, KeyValueStore] =
    RocksKeyValueStore.layer(path)
