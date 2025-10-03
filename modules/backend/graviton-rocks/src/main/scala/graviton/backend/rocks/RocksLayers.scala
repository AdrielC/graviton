package graviton.backend.rocks

import zio.{Layer, ZLayer}

object RocksLayers:
  val live: Layer[Nothing, RocksKeyValueStore] = ZLayer.succeed(new RocksKeyValueStore)
