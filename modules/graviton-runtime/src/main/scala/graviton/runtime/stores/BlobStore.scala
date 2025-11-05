package graviton.runtime.stores

import graviton.core.keys.BinaryKey
import graviton.runtime.model.{BlobStat, BlobWritePlan, BlobWriteResult}
import zio.*
import zio.stream.*

trait BlobStore:
  def put(plan: BlobWritePlan = BlobWritePlan()): ZSink[Any, Throwable, Byte, Chunk[Byte], BlobWriteResult]
  def get(key: BinaryKey): ZStream[Any, Throwable, Byte]
  def stat(key: BinaryKey): ZIO[Any, Throwable, Option[BlobStat]]
  def delete(key: BinaryKey): ZIO[Any, Throwable, Unit]
