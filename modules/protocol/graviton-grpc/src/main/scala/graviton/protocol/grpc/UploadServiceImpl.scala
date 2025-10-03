package graviton.protocol.grpc

import graviton.runtime.stores.BlobStore
import zio.Chunk
import zio.stream.ZStream
import zio.ZIO

final case class UploadServiceImpl(blobStore: BlobStore):
  def upload(stream: ZStream[Any, Throwable, Chunk[Byte]]): ZIO[Any, Throwable, Unit] =
    blobStore.put(stream.flattenChunks).unit
