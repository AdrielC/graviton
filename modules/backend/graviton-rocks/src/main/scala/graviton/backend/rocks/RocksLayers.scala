package graviton.backend.rocks

import graviton.runtime.kv.KeyValueStore
import zio.{ZLayer, Scope}

import java.nio.file.Path

object RocksLayers:
  def live(path: Path): ZLayer[Scope, Throwable, KeyValueStore] =
    ZLayer.fromZIO(RocksKeyValueStore.open(path))
