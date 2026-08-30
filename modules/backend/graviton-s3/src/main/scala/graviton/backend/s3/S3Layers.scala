package graviton.backend.s3

import software.amazon.awssdk.services.s3.S3Client
import graviton.runtime.stores.TransferBudget
import zio.ZLayer

object S3Layers:
  def live(config: S3ObjectStoreConfig): ZLayer[S3Client & TransferBudget, Nothing, S3MutableObjectStore] =
    ZLayer.fromFunction((client: S3Client, budget: TransferBudget) => new S3MutableObjectStore(client, config, budget))
