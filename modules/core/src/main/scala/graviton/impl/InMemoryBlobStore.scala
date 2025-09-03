package graviton.impl

import graviton.*
import zio.*
import zio.stream.*

final class InMemoryBlobStore private (
    ref: Ref[Map[BlockKey, Chunk[Byte]]],
    val id: BlobStoreId
) extends BlobStore:

  def status: UIO[BlobStoreStatus] = ZIO.succeed(BlobStoreStatus.Operational)

  def read(key: BlockKey): IO[Throwable, Option[Bytes]] =
    ref.get.map(_.get(key).map(ZStream.fromChunk))

  def write(key: BlockKey, data: Bytes): IO[Throwable, Unit] =
    data.runCollect.flatMap(ch => ref.update(_.updated(key, ch))).unit

  def delete(key: BlockKey): IO[Throwable, Boolean] =
    ref.modify { m =>
      if m.contains(key) then (true, m - key) else (false, m)
    }

object InMemoryBlobStore:
  def make(id: String = "mem-1"): UIO[InMemoryBlobStore] =
    Ref
      .make(Map.empty[BlockKey, Chunk[Byte]])
      .map(new InMemoryBlobStore(_, BlobStoreId(id)))
