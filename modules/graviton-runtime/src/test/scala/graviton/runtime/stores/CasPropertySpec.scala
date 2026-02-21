package graviton.runtime.stores

import graviton.core.bytes.*
import graviton.core.keys.*
import graviton.core.types.*
import graviton.streams.Chunker
import zio.*
import zio.stream.*
import zio.test.*

/**
 * Property-based tests for the CAS pipeline.
 *
 * Uses zio-test Gen to generate random data and chunk sizes,
 * verifying that the round-trip property holds for ALL inputs.
 */
object CasPropertySpec extends ZIOSpecDefault:

  private val genData: Gen[Any, Chunk[Byte]] =
    Gen.chunkOfBounded(1, 50_000)(Gen.byte)

  private val genChunkSize: Gen[Any, Int] =
    Gen.int(64, 8192)

  override def spec: Spec[TestEnvironment, Any] =
    suite("CAS Property Tests")(
      test("round-trip: data == read(write(data)) for any input") {
        check(genData, genChunkSize) { (data, chunkSize) =>
          val chunker = Chunker.fixed(UploadChunkSize.applyUnsafe(chunkSize))
          for
            blockStore <- InMemoryBlockStore.make
            repo       <- InMemoryBlobManifestRepo.make
            blobStore   = new CasBlobStore(blockStore, repo)

            result <- Chunker.locally(chunker) {
                        ZStream.fromChunk(data).run(blobStore.put())
                      }

            readBack <- blobStore.get(result.key).runCollect
          yield assertTrue(readBack == data)
        }
      },
      test("content-addressed: same data always produces same key") {
        check(genData) { data =>
          for
            blockStore <- InMemoryBlockStore.make
            repo       <- InMemoryBlobManifestRepo.make
            blobStore   = new CasBlobStore(blockStore, repo)

            result1 <- ZStream.fromChunk(data).run(blobStore.put())
            result2 <- ZStream.fromChunk(data).run(blobStore.put())
          yield assertTrue(result1.key == result2.key)
        }
      },
      test("different data produces different keys") {
        check(genData, genData) { (data1, data2) =>
          for
            blockStore <- InMemoryBlockStore.make
            repo       <- InMemoryBlobManifestRepo.make
            blobStore   = new CasBlobStore(blockStore, repo)

            result1 <- ZStream.fromChunk(data1).run(blobStore.put())
            result2 <- ZStream.fromChunk(data2).run(blobStore.put())
          yield
            // Only meaningful when the data is actually different
            if data1 == data2 then assertTrue(result1.key == result2.key)
            else assertTrue(result1.key != result2.key)
        }
      },
      test("manifest total span equals data length") {
        check(genData, genChunkSize) { (data, chunkSize) =>
          val chunker = Chunker.fixed(UploadChunkSize.applyUnsafe(chunkSize))
          for
            blockStore <- InMemoryBlockStore.make
            repo       <- InMemoryBlobManifestRepo.make
            blobStore   = new CasBlobStore(blockStore, repo)

            result <- Chunker.locally(chunker) {
                        ZStream.fromChunk(data).run(blobStore.put())
                      }

            blobKey   = result.key.asInstanceOf[BinaryKey.Blob]
            manifest <- repo.get(blobKey).someOrFail(new NoSuchElementException("manifest missing"))

            totalSpan = manifest.entries.foldLeft(0L) { (acc, e) =>
                          acc + (e.span.endInclusive.value - e.span.startInclusive.value + 1L)
                        }
          yield assertTrue(totalSpan == data.length.toLong)
        }
      },
      test("ingest stats totalBytes matches input length") {
        check(genData) { data =>
          for
            blockStore <- InMemoryBlockStore.make
            repo       <- InMemoryBlobManifestRepo.make
            blobStore   = new CasBlobStore(blockStore, repo)

            result <- ZStream.fromChunk(data).run(blobStore.put())
          yield assertTrue(result.stats.totalBytes == data.length.toLong)
        }
      },
    ) @@ TestAspect.samples(20) // Keep test count reasonable for CI
