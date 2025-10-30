package graviton.backend.pg

import zio.{Layer, ZLayer}

object PgLayers:
  val live: Layer[Nothing, PgMutableObjectStore] = ZLayer.succeed(new PgMutableObjectStore)
