import graviton.core.attributes.{BinaryAttributes, Source, Tracked}
import graviton.core.bytes.{HashAlgo, Hasher}
import graviton.core.keys.{BinaryKey, KeyBits}
import graviton.core.model.{BlockBuilder, ByteConstraints}
import graviton.runtime.model.{BlockBatchResult, CanonicalBlock}
import graviton.runtime.stores.BlockStore
import graviton.streams.Chunker
import zio._
import zio.stream._

final case class Ingest(blockStore: BlockStore):
  private def wrapEither[A](either: Either[String, A]): Task[A] =
    ZIO.fromEither(either.left.map(msg => new IllegalArgumentException(msg)))

  private def canonicalBlock(block: Chunk[Byte], attrs: BinaryAttributes): Task[CanonicalBlock] =
    wrapEither {
      for
        digest     <- Hasher.memory(HashAlgo.Sha256).update(block.toArray).result
        bits       <- KeyBits.create(HashAlgo.Sha256, digest, block.length.toLong)
        key        <- BinaryKey.block(bits)
        chunkCount <- ByteConstraints.refineChunkCount(1L)
        tracked     = attrs
                        .upsertSize(Tracked.now(ByteConstraints.unsafeFileSize(block.length.toLong), Source.Derived))
                        .upsertChunkCount(Tracked.now(chunkCount, Source.Derived))
        canonical  <- CanonicalBlock.make(key, block, tracked)
      yield canonical
    }

  def run(bytes: ZStream[Any, Throwable, Byte]): Task[BlockBatchResult] =
    val attrs = BinaryAttributes.empty
    val sink  = blockStore.putBlocks()

    for
      chunkSize <- wrapEither(ByteConstraints.refineUploadChunkSize(1 * 1024 * 1024))
      result    <- bytes
                     .via(Chunker.fixed(chunkSize))
                     .mapChunks(BlockBuilder.chunkify(_))
                     .mapZIO(block => canonicalBlock(block.bytes, attrs))
                     .run(sink)
    yield result
