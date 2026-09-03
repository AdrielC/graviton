import graviton.core.attributes.BinaryAttributes
import graviton.core.bytes.{HashAlgo, Hasher}
import graviton.core.keys.{BinaryKey, KeyBits}
import graviton.core.model.Block
import graviton.core.model.Block.*
import graviton.core.types.{ChunkCount, FileSize, UploadChunkSize}
import graviton.runtime.model.{BlockBatchResult, CanonicalBlock}
import graviton.runtime.stores.BlockStore
import graviton.streams.Chunker
import zio._
import zio.stream._

final case class Ingest(blockStore: BlockStore):

  private def canonicalBlock(block: Block, attrs: BinaryAttributes): ZIO[Hasher.Provider, String, CanonicalBlock] =
    for
      hasher     <- Hasher.Provider.make(HashAlgo.runtimeDefault).mapError(_.message)
      _           = hasher.update(block.bytes)
      hashed     <- ZIO.fromEither(hasher.hashed.left.map(_.message))
      bits        = KeyBits.fromHashed(hashed)
      key        <- ZIO.fromEither(BinaryKey.block(bits))
      chunkCount <- ZIO.fromEither(ChunkCount.either(1L))
      size       <- ZIO.fromEither(FileSize.either(block.length.toLong))
      confirmed   = attrs
                      .confirmSize(size)
                      .confirmChunkCount(chunkCount)
      canonical  <- ZIO.fromEither(CanonicalBlock.make(key, block.bytes, confirmed))
    yield canonical

  def run(bytes: ZStream[Any, Throwable, Byte]): ZIO[Hasher.Provider, Throwable, BlockBatchResult] =
    val attrs     = BinaryAttributes.empty
    val sink      = blockStore.putBlocks()
    val chunkSize = UploadChunkSize(1 * 1024 * 1024) // compile-time refined

    for result <- bytes
                    .via(Chunker.fixed(chunkSize).pipeline.mapError(Chunker.toThrowable))
                    .mapZIO(block => canonicalBlock(block, attrs).mapError(new IllegalArgumentException(_)))
                    .run(sink)
    yield result
