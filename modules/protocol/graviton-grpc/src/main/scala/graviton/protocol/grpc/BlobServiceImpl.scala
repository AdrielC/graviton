package graviton.protocol.grpc

import graviton.core.keys.BinaryKey
import graviton.runtime.model.BlobStat
import graviton.runtime.stores.BlobStore
import zio.ZIO
import zio.stream.ZStream

final case class BlobServiceImpl(blobStore: BlobStore):
  def get(key: BinaryKey): ZStream[Any, Throwable, Byte]          = blobStore.get(key)
  def stat(key: BinaryKey): ZIO[Any, Throwable, Option[BlobStat]] = blobStore.stat(key)
