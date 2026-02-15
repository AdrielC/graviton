package graviton.runtime.stores

import graviton.core.bytes.*
import graviton.core.keys.*
import graviton.core.types.*
import graviton.runtime.model.*
import graviton.streams.Chunker
import zio.*
import zio.stream.*
import zio.test.*

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

/**
 * End-to-end integration tests for the CAS pipeline:
 *   ingest -> manifest -> read back -> verify integrity
 *
 * These tests exercise the full flow from raw bytes to blob key and back,
 * proving the Transducer pipeline and CasBlobStore produce correct, verifiable results.
 */
object CasRoundTripSpec extends ZIOSpecDefault:

  override def spec: Spec[TestEnvironment, Any] =
    suite("CAS Round-Trip")(
      suite("in-memory")(
        test("small text round-trips through CAS") {
          val text = "Hello, Graviton! Content-addressed storage is the future."
          roundTrip(text.getBytes(StandardCharsets.UTF_8), chunkSize = 32)
        },
        test("binary data round-trips through CAS") {
          // Pattern that exercises rechunker edge cases
          val data = Array.tabulate(5000)(i => (i % 256).toByte)
          roundTrip(data, chunkSize = 1024)
        },
        test("exact multiple of chunk size round-trips") {
          val data = Array.fill(2048)(42.toByte)
          roundTrip(data, chunkSize = 1024)
        },
        test("single-byte blob round-trips") {
          roundTrip(Array[Byte](0x42), chunkSize = 64)
        },
        test("large blob round-trips and verifies") {
          val data = Array.tabulate(100_000)(i => (i % 251).toByte) // prime modulus for good distribution
          roundTrip(data, chunkSize = 4096)
        },
        test("stat returns correct metadata for stored blob") {
          val data = Chunk.fromArray("stat-me".getBytes(StandardCharsets.UTF_8))

          for
            blockStore <- InMemoryBlockStore.make
            repo       <- InMemoryBlobManifestRepo.make
            blobStore   = new CasBlobStore(blockStore, repo)

            result  <- ZStream.fromChunk(data).run(blobStore.put())
            blobKey  = result.key.asInstanceOf[BinaryKey.Blob]
            statOpt <- blobStore.stat(blobKey)
          yield assertTrue(
            statOpt.isDefined,
            statOpt.get.size.value == data.length.toLong,
            statOpt.get.digest != Digest.empty,
          )
        },
        test("duplicate data produces same blob key") {
          val data = Chunk.fromArray("deduplicate-me".getBytes(StandardCharsets.UTF_8))

          for
            blockStore <- InMemoryBlockStore.make
            repo       <- InMemoryBlobManifestRepo.make
            blobStore   = new CasBlobStore(blockStore, repo)

            result1 <- ZStream.fromChunk(data).run(blobStore.put())
            result2 <- ZStream.fromChunk(data).run(blobStore.put())
          yield assertTrue(result1.key == result2.key)
        },
        test("different data produces different blob keys") {
          for
            blockStore <- InMemoryBlockStore.make
            repo       <- InMemoryBlobManifestRepo.make
            blobStore   = new CasBlobStore(blockStore, repo)

            result1 <- ZStream.fromChunk(Chunk.fromArray("data-one".getBytes)).run(blobStore.put())
            result2 <- ZStream.fromChunk(Chunk.fromArray("data-two".getBytes)).run(blobStore.put())
          yield assertTrue(result1.key != result2.key)
        },
        test("ingest stats report correct byte count and block info") {
          val data = Chunk.fromArray(Array.tabulate(3000)(i => (i % 200).toByte))

          for
            blockStore <- InMemoryBlockStore.make
            repo       <- InMemoryBlobManifestRepo.make
            blobStore   = new CasBlobStore(blockStore, repo)
            chunker     = Chunker.fixed(UploadChunkSize(1024))

            result <- Chunker.locally(chunker) {
                        ZStream.fromChunk(data).run(blobStore.put())
                      }

            stats = result.stats
          yield assertTrue(
            stats.totalBytes == data.length.toLong,
            stats.blockCount == 3, // 1024 + 1024 + 952
            stats.freshBlocks == 3,
            stats.duplicateBlocks == 0,
            stats.durationSeconds >= 0.0,
            stats.dedupRatio == 0.0,
          )
        },
        test("ingest stats track dedup when same data ingested twice") {
          val data = Chunk.fromArray("dedup-stats-test".getBytes(java.nio.charset.StandardCharsets.UTF_8))

          for
            blockStore <- InMemoryBlockStore.make
            repo       <- InMemoryBlobManifestRepo.make
            blobStore   = new CasBlobStore(blockStore, repo)

            result1 <- ZStream.fromChunk(data).run(blobStore.put())
            result2 <- ZStream.fromChunk(data).run(blobStore.put())
          yield assertTrue(
            result1.stats.freshBlocks > 0,
            result1.stats.duplicateBlocks == 0,
            result2.stats.freshBlocks == 0,
            result2.stats.duplicateBlocks > 0,
            result2.stats.dedupRatio == 1.0,
          )
        },
        test("delete removes manifest so stat returns None") {
          val data = Chunk.fromArray("delete-me".getBytes(StandardCharsets.UTF_8))

          for
            blockStore <- InMemoryBlockStore.make
            repo       <- InMemoryBlobManifestRepo.make
            blobStore   = new CasBlobStore(blockStore, repo)

            result     <- ZStream.fromChunk(data).run(blobStore.put())
            blobKey     = result.key.asInstanceOf[BinaryKey.Blob]
            statBefore <- blobStore.stat(blobKey)
            _          <- blobStore.delete(blobKey)
            statAfter  <- blobStore.stat(blobKey)
          yield assertTrue(
            statBefore.isDefined,
            statAfter.isEmpty,
          )
        },
      ),
      suite("filesystem-backed")(
        test("FsBlockStore + CasBlobStore full round-trip") {
          val data = Array.tabulate(10_000)(i => (i % 173).toByte)
          withTempDir { root =>
            val chunker = Chunker.fixed(UploadChunkSize(2048))
            for
              blockStore <- ZIO.succeed(new FsBlockStore(root))
              repo       <- InMemoryBlobManifestRepo.make
              blobStore   = new CasBlobStore(blockStore, repo)

              result <- Chunker.locally(chunker) {
                          ZStream.fromChunk(Chunk.fromArray(data)).run(blobStore.put())
                        }

              blobKey = result.key.asInstanceOf[BinaryKey.Blob]

              // Read back all bytes
              readBack <- blobStore.get(blobKey).runCollect

              // Verify stat
              stat <- blobStore.stat(blobKey)
            yield assertTrue(
              readBack.toArray.sameElements(data),
              stat.isDefined,
              stat.get.size.value == data.length.toLong,
            )
          }
        },
        test("insertFile round-trips a temp file") {
          val data = Array.tabulate(5000)(i => (i % 97).toByte)
          withTempDir { root =>
            val chunker = Chunker.fixed(UploadChunkSize(1024))
            for
              blockStore <- ZIO.succeed(new FsBlockStore(root))
              repo       <- InMemoryBlobManifestRepo.make
              blobStore   = new CasBlobStore(blockStore, repo)

              // Write test data to a temp file
              tempFile <- ZIO.attemptBlocking {
                            val f = Files.createTempFile(root, "ingest-", ".dat")
                            Files.write(f, data)
                            f
                          }

              result <- Chunker.locally(chunker) {
                          StoreOps.insertFile(blobStore)(tempFile)
                        }

              blobKey   = result.key.asInstanceOf[BinaryKey.Blob]
              readBack <- blobStore.get(blobKey).runCollect
            yield assertTrue(readBack.toArray.sameElements(data))
          }
        },
      ),
    )

  /** Core round-trip test: write -> read -> byte-compare. */
  private def roundTrip(
    data: Array[Byte],
    chunkSize: Int,
  ): ZIO[Any, Throwable, TestResult] =
    val chunker   = Chunker.fixed(UploadChunkSize.applyUnsafe(chunkSize))
    val inputData = Chunk.fromArray(data)

    for
      blockStore <- InMemoryBlockStore.make
      repo       <- InMemoryBlobManifestRepo.make
      blobStore   = new CasBlobStore(blockStore, repo)

      result <- Chunker.locally(chunker) {
                  ZStream.fromChunk(inputData).run(blobStore.put())
                }

      blobKey = result.key.asInstanceOf[BinaryKey.Blob]

      // Verify manifest was stored
      manifest <- repo.get(blobKey).someOrFail(new NoSuchElementException("Manifest missing"))

      // Read back all bytes
      readBack <- blobStore.get(blobKey).runCollect

      // Compute expected hash for comparison
      hasher <- ZIO.fromEither(Hasher.systemDefault).mapError(msg => new IllegalStateException(msg))
      _       = hasher.update(data)
      digest <- ZIO.fromEither(hasher.digest).mapError(msg => new IllegalArgumentException(msg))

      // Manifest entries should cover the full blob
      totalSpanBytes = manifest.entries.foldLeft(0L)((acc, e) => acc + (e.span.endInclusive.value - e.span.startInclusive.value + 1L))
    yield assertTrue(
      readBack.length == data.length,
      readBack.toArray.sameElements(data),
      manifest.entries.nonEmpty,
      totalSpanBytes == data.length.toLong,
      blobKey.bits.digest.hex.value == digest.hex.value,
    )

  private def withTempDir[A](f: Path => ZIO[Any, Throwable, A]): ZIO[Any, Throwable, A] =
    ZIO.acquireReleaseWith(
      ZIO.attemptBlocking(Files.createTempDirectory("graviton-cas-rt-"))
    )(dir =>
      ZIO.attemptBlocking {
        java.nio.file.Files
          .walk(dir)
          .sorted(java.util.Comparator.reverseOrder())
          .forEach { p =>
            val _ = java.nio.file.Files.deleteIfExists(p)
          }
      }.orDie
    )(f)
