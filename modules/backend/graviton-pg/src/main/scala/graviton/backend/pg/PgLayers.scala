package graviton.backend.pg

import zio.ZLayer

import javax.sql.DataSource

object PgLayers:
  val live: ZLayer[DataSource, Nothing, PgMutableObjectStore] =
    ZLayer.fromFunction((dataSource: DataSource) => new PgMutableObjectStore(dataSource))
