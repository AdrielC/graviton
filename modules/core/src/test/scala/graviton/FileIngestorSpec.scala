package graviton

import graviton.chunking.FixedChunker
import graviton.impl.InMemoryBlobStore
import graviton.ingest.FileIngestor
import zio.*
import zio.stream.*
import zio.test.*
import zio.Chunk
import graviton.core.BinaryAttributes
import graviton.HashAlgorithm
import graviton.Manifest
import graviton.BlockKey
// import graviton.core.BinaryKeyMatcher
import graviton.ingest.FileIngestorLive
import graviton.chunking.Chunker
import graviton.impl.InMemoryBlockResolver
import graviton.impl.BlockState
import graviton.impl.InMemoryBlockStore
// DiskCacheStore
import zio.prelude.NonEmptySortedMap
// import graviton.impl.InMemoryCacheStore

object FileIngestorSpec extends ZIOSpec[Reloadable[FileIngestorLive] & BlockStore] {

  type Environment = Reloadable[FileIngestorLive] & BlockStore

  inline def chunker: Chunker = Chunker.fastCdc(
    Chunker.Bounds.default,
    normalization = 2,
    window = 64,
  )

  inline def chunkerLayer: ZLayer[Any, Nothing, Chunker] = ZLayer.succeed[Chunker](chunker)

  inline override def bootstrap: ZLayer[Any, Nothing, Environment] =
    ZLayer.make[Environment](
      ZLayer.fromZIO(Ref.Synchronized.make(Map.empty[BlockKey, BlockState])),
      ZLayer.succeed(Map.empty[BlobStoreId, BlobStore]),
      InMemoryBlockStore.layer,
      chunkerLayer,
      InMemoryBlockResolver.default,
      InMemoryBlobStore.layer,
      FileIngestorLive.layer.reloadableManual,
    )

  // private def withBlockStore[E, A](effect: BlockStore => ZIO[Environment, E, A]): ZIO[Any, E, A] =
  //   (for {
  //     _ <- Reloadable.reload[FileIngestorLive].catchAll(_ => ZIO.die(Throwable("failed to reload block store")))
  //     r <- Reloadable.get[FileIngestorLive].catchAll(_ => ZIO.die(Throwable("failed to get block store")))
  //     result     <- effect(r.blockStore)
  //   } yield result)
  //   .provide(bootstrap)

  private def withFileIngestor[E, A](effect: ZEnvironment[FileIngestor & BlockStore] => ZIO[Environment, E, A]): ZIO[Any, E, A] =
    (for {
      _      <- Reloadable.reload[FileIngestorLive].catchAll(_ => ZIO.die(Throwable("failed to reload block store")))
      r      <- Reloadable.get[FileIngestorLive].catchAll(_ => ZIO.die(Throwable("failed to get block store")))
      result <- effect(ZEnvironment(r, r.blockStore))
    } yield result)
      .provide(bootstrap)

  private def bytesOf(str: String): Bytes = Bytes(ZStream.fromChunk(Chunk.fromArray(str.getBytes("UTF-8"))))

  override def spec: Spec[Scope, Any] =
    suite("FileIngestorSpec")(
      test("ingests content and reconstructs it exactly") {
        val payload = "Hello Graviton!"
        withFileIngestor { env =>
          val chunker = FixedChunker(4)
          (for {
            result <- FileIngestor.ingest(bytesOf(payload), 
            BinaryAttributes.empty, NonEmptySortedMap(HashAlgorithm.Blake3 -> None), Some(chunker))
            data   <- FileIngestor.materialize(result.manifest)
            text   <- data.runCollect.map(bytes => new String(bytes.toArray, "UTF-8"))
          } yield assertTrue(text == payload) && assertTrue(result.totalBlocks > 0))
            .provideEnvironment(env)
        }
      },
      test("computes deterministic blob key and manifest offsets") {
        val sample = "a" * 20
        withFileIngestor { env =>
          val chunker = FixedChunker(5)
          (for {
            result <- FileIngestor.ingest(bytesOf(sample), BinaryAttributes.empty, NonEmptySortedMap(HashAlgorithm.Blake3 -> None), Some(chunker))
            entries = result.manifest.entries
            offsets = entries.map(_.offset)
            sizes   = entries.map(_.size)
          } yield assertTrue(entries.nonEmpty) &&
            assertTrue(offsets.map(_.toLong) == offsets.map(_.toLong).sorted) &&
            assertTrue(sizes.forall(_ <= 5)) &&
            assertTrue(result.blobKey.size == sample.length.toLong))
            .provideEnvironment(env)
        }
      },
      test("deduplicates identical blocks via the block store") {
        val sample = "repeatable content" * 5
        withFileIngestor { env =>
          val chunker = FixedChunker(8)
          (for {
            _          <- FileIngestor.ingest(bytesOf(sample), BinaryAttributes.empty, NonEmptySortedMap(HashAlgorithm.Blake3 -> None), Some(chunker))
            blockStore <- ZIO.service[BlockStore]
            keys       <- blockStore
                            .list(BlockKeySelector(prefix = None, suffix = None, algorithm = Some(HashAlgorithm.Blake3), size = None))
                            .runCollect
            unique      = keys.distinct
          } yield assertTrue(unique.size == keys.size))
            .provideEnvironment(env)
        }
      },
    )
}
