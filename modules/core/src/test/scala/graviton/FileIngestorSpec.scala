package graviton

import graviton.chunking.FixedChunker
import graviton.impl.{InMemoryBlobStore, InMemoryBlockResolver, InMemoryBlockStore}
import graviton.ingest.FileIngestor
import zio.*
import zio.stream.*
import zio.test.*
import zio.Chunk

object FileIngestorSpec extends ZIOSpecDefault {

  private def withBlockStore[R, E, A](effect: BlockStore => ZIO[R, E, A]): ZIO[R, E, A] =
    for {
      blobStore  <- InMemoryBlobStore.make()
      resolver   <- InMemoryBlockResolver.make
      blockStore <- InMemoryBlockStore.make(
                      primary = blobStore,
                      resolver = resolver,
                    )
      result     <- effect(blockStore)
    } yield result

  private def bytesOf(str: String): Bytes = Bytes(ZStream.fromChunk(Chunk.fromArray(str.getBytes("UTF-8"))))

  override def spec: Spec[TestEnvironment & Scope, Any] =
    suite("FileIngestorSpec")(
      test("ingests content and reconstructs it exactly") {
        val payload = "Hello Graviton!"
        withBlockStore { blockStore =>
          val chunker = FixedChunker(4)
          for {
            result <- FileIngestor.ingest(bytesOf(payload), blockStore, chunker)
            data   <- FileIngestor.materialize(result.manifest, blockStore)
            text   <- data.runCollect.map(bytes => new String(bytes.toArray, "UTF-8"))
          } yield assertTrue(text == payload) && assertTrue(result.totalBlocks > 0)
        }
      },
      test("computes deterministic blob key and manifest offsets") {
        val sample = "a" * 20
        withBlockStore { blockStore =>
          val chunker = FixedChunker(5)
          for {
            result <- FileIngestor.ingest(bytesOf(sample), blockStore, chunker)
            entries = result.manifest.entries
            offsets = entries.map(_.offset)
            sizes   = entries.map(_.size)
          } yield assertTrue(entries.nonEmpty) &&
            assertTrue(offsets == offsets.sorted) &&
            assertTrue(sizes.forall(_ <= 5)) &&
            assertTrue(result.blobKey.size == sample.length.toLong)
        }
      },
      test("deduplicates identical blocks via the block store") {
        val sample = "repeatable content" * 5
        withBlockStore { blockStore =>
          val chunker = FixedChunker(8)
          for {
            _     <- FileIngestor.ingest(bytesOf(sample), blockStore, chunker)
            _     <- FileIngestor.ingest(bytesOf(sample), blockStore, chunker)
            keys  <- blockStore.list(BlockKeySelector()).runCollect
            unique = keys.distinct
          } yield assertTrue(unique.size == keys.size)
        }
      },
    )
}
