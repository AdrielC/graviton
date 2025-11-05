package graviton

import graviton.chunking.FixedChunker
import graviton.impl.{InMemoryBlobStore, InMemoryBlockResolver, InMemoryBlockStore}
import graviton.ingest.FileIngestor
import zio.*
import zio.stream.*
import zio.test.*
import zio.Chunk
import graviton.core.BinaryAttributes
import graviton.HashAlgorithm
import graviton.Manifest
import graviton.BlockKey
import graviton.core.BlockStore
import graviton.core.BinaryKeyMatcher
import graviton.ingest.FileIngestorLive
import graviton.chunking.Chunker
import graviton.impl.InMemoryBlockResolver
// DiskCacheStore

import graviton.fs.DiskCacheStore

object FileIngestorSpec extends ZIOSpec[FileIngestor] {

  transparent inline def bootstrap: ZLayer[Any, Any, FileIngestor] =
    ZLayer.make[FileIngestor](
      InMemoryBlockStore.layer,
      Chunker.default,
      InMemoryBlockResolver.default.reloadableManual,
      DiskCacheStore.default,
      FileIngestorLive.layer,
    )

  private def withBlockStore[R, E, A](effect: BlockStore => ZIO[R, E, A]): ZIO[R, E, A] =
    for {
      blobStore  <- InMemoryBlobStore.make()
      resolver   <- InMemoryBlockResolver.make
      blockStore <- InMemoryBlockStore.make(
                      primary = blobStore,
                      resolver = resolver,
                    )
      result     <- effect(blockStore: BlockStore)
    } yield result  

  private def bytesOf(str: String): Bytes = Bytes(ZStream.fromChunk(Chunk.fromArray(str.getBytes("UTF-8"))))

  override def spec: Spec[TestEnvironment & Scope, Any] =
    suite("FileIngestorSpec")(
      test("ingests content and reconstructs it exactly") {
        val payload = "Hello Graviton!"
        withBlockStore { blockStore =>
          val chunker = FixedChunker(4)
          for {
            result <- FileIngestor.ingest(bytesOf(payload), BinaryAttributes.empty, HashAlgorithm.Blake3)
            data   <- FileIngestor.materialize(result.manifest)
            text   <- data.runCollect.map(bytes => new String(bytes.toArray, "UTF-8"))
          } yield assertTrue(text == payload) && assertTrue(result.totalBlocks > 0)
        }
      },
      test("computes deterministic blob key and manifest offsets") {
        val sample = "a" * 20
        withBlockStore { blockStore =>
          val chunker = FixedChunker(5)
          for {
            result <- FileIngestor.ingest(bytesOf(sample), BinaryAttributes.empty, HashAlgorithm.Blake3)
            entries = result.manifest.entries
            offsets = entries.map(_.offset)
            sizes   = entries.map(_.size)
          } yield assertTrue(entries.nonEmpty) &&
            assertTrue(offsets.map(_.toLong) == offsets.map(_.toLong).sorted) &&
            assertTrue(sizes.forall(_ <= 5)) &&
            assertTrue(result.blobKey.size == sample.length.toLong)
        }
      },
      test("deduplicates identical blocks via the block store") {
        val sample = "repeatable content" * 5
        withBlockStore { blockStore =>
          val chunker = FixedChunker(8)
          for {
            _     <- FileIngestor.ingest(bytesOf(sample), 
            BinaryAttributes.empty, HashAlgorithm.Blake3)
            keys  <- blockStore.listKeys(
              BinaryKeyMatcher.ByAlg(HashAlgorithm.Blake3)
            ).runCollect
            unique = keys.distinct
          } yield assertTrue(unique.size == keys.size)
        }
      },
    )
}
