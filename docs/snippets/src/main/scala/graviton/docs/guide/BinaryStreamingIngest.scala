import graviton.core.attributes.BinaryAttributes
import graviton.core.bytes.Hasher
import graviton.core.keys.{BinaryKey, KeyBits}
import graviton.core.model.ByteConstraints
import graviton.core.model.Block.*
import graviton.runtime.model.{BlockBatchResult, CanonicalBlock}
import graviton.runtime.stores.BlockStore
import graviton.streams.Chunker
import zio._
import zio.stream._

extension [E, A](either: Either[E, A])
  def toTask(using E <:< String): Task[A] = ZIO.fromEither(either.left.map(msg => new IllegalArgumentException(msg)))

final case class Ingest(blockStore: BlockStore):

  private def canonicalBlock(block: Chunk[Byte], attrs: BinaryAttributes): Either[String, CanonicalBlock] =
    for
      hasher     <- Hasher.systemDefault
      algo        = hasher.algo
      _           = hasher.update(block.toArray)
      digest     <- hasher.digest
      bits       <- KeyBits.create(algo, digest, block.length.toLong)
      key        <- BinaryKey.block(bits)
      chunkCount <- ByteConstraints.refineChunkCount(1L)
      confirmed   = attrs
                      .confirmSize(ByteConstraints.unsafeFileSize(block.length.toLong))
                      .confirmChunkCount(chunkCount)
      canonical  <- CanonicalBlock.make(key, block, confirmed)
    yield canonical

  def run(bytes: ZStream[Any, Throwable, Byte]): Task[BlockBatchResult] =
    val attrs = BinaryAttributes.empty
    val sink  = blockStore.putBlocks()

    for
      chunkSize <- ByteConstraints.refineUploadChunkSize(1 * 1024 * 1024).toTask
      result    <- bytes
                     .via(Chunker.fixed(chunkSize))
                     .mapZIO(block => canonicalBlock(block.bytes, attrs).toTask)
                     .run(sink)
    yield result
