package graviton.backend.s3

import zio.{Layer, ZLayer}

object S3Layers:
  val live: Layer[Nothing, S3MutableObjectStore] = ZLayer.succeed(new S3MutableObjectStore)
