package graviton.runtime.stores

import graviton.core.keys.BinaryKey
import graviton.core.types.UploadChunkSize
import graviton.streams.Chunker
import zio.*
import zio.stream.ZStream
import zio.test.*

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

object FsBlobManifestRepoSpec extends ZIOSpecDefault:

  override def spec: Spec[TestEnvironment, Any] =
    suite("FsBlobManifestRepo")(
      test("persists a CAS round-trip across fresh store instances") {
        withTempDir { root =>
          val data    = Chunk.fromArray(Array.tabulate(32_000)(index => (index % 251).toByte))
          val chunker = Chunker.fixed(UploadChunkSize(4096))

          for
            firstStore  <- makeStore(root)
            result      <- Chunker.locally(chunker)(ZStream.fromChunk(data).run(firstStore.put()))
            secondStore <- makeStore(root)
            readBack    <- secondStore.get(result.key).runCollect
            stat        <- secondStore.stat(result.key)
          yield assertTrue(
            readBack == data,
            stat.exists(_.size.value == data.length.toLong),
          )
        }
      },
      test("writes a framed manifest and preserves its ingestion time") {
        withTempDir { root =>
          val data = Chunk.fromArray("durable-manifest".getBytes(StandardCharsets.UTF_8))

          for
            ingestedAt <- Clock.instant
            store      <- makeStore(root)
            result     <- ZStream.fromChunk(data).run(store.put())
            blob        = result.key.asInstanceOf[BinaryKey.Blob]
            repo        = new FsBlobManifestRepo(root)
            stored     <- repo.get(blob).someOrFail(new NoSuchElementException("manifest missing"))
            exists     <- ZIO.attemptBlocking(Files.isRegularFile(repo.pathFor(blob)))
          yield assertTrue(
            exists,
            stored.manifest.size == data.length.toLong,
            stored.ingestedAt == ingestedAt,
          )
        }
      },
      test("delete removes the manifest but retains deduplicated blocks") {
        withTempDir { root =>
          val data = Chunk.fromArray("manifest-only-delete".getBytes(StandardCharsets.UTF_8))

          for
            blockStore <- ZIO.succeed(new FsBlockStore(root))
            repo        = new FsBlobManifestRepo(root)
            store       = new CasBlobStore(blockStore, repo)
            result     <- ZStream.fromChunk(data).run(store.put())
            blob        = result.key.asInstanceOf[BinaryKey.Blob]
            manifest   <- repo.get(blob).someOrFail(new NoSuchElementException("manifest missing"))
            block       = manifest.manifest.entries.head.key.asInstanceOf[BinaryKey.Block]
            _          <- store.delete(blob)
            after      <- repo.get(blob)
            blockStill <- blockStore.exists(block)
          yield assertTrue(after.isEmpty, blockStill)
        }
      },
      test("rejects a corrupted manifest instead of returning partial data") {
        withTempDir { root =>
          val data = Chunk.fromArray("corrupt-me".getBytes(StandardCharsets.UTF_8))

          for
            store   <- makeStore(root)
            result  <- ZStream.fromChunk(data).run(store.put())
            blob     = result.key.asInstanceOf[BinaryKey.Blob]
            repo     = new FsBlobManifestRepo(root)
            _       <- ZIO.attemptBlocking(Files.write(repo.pathFor(blob), Array[Byte](1, 2, 3)))
            failure <- repo.get(blob).exit
          yield assertTrue(failure.isFailure)
        }
      },
      test("concurrent idempotent writes remain decodable") {
        withTempDir { root =>
          val data = Chunk.fromArray("concurrent-manifest".getBytes(StandardCharsets.UTF_8))

          for
            store    <- makeStore(root)
            result   <- ZStream.fromChunk(data).run(store.put())
            blob      = result.key.asInstanceOf[BinaryKey.Blob]
            repo      = new FsBlobManifestRepo(root)
            stored   <- repo.get(blob).someOrFail(new NoSuchElementException("manifest missing"))
            now      <- Clock.instant
            _        <- ZIO.foreachParDiscard(1 to 12)(_ => repo.put(blob, stored.manifest, now))
            reloaded <- repo.get(blob)
          yield assertTrue(reloaded.exists(_.manifest == stored.manifest))
        }
      },
    )

  private def makeStore(root: Path): UIO[BlobStore] =
    ZIO.succeed(new CasBlobStore(new FsBlockStore(root), new FsBlobManifestRepo(root)))

  private def withTempDir[A](f: Path => ZIO[Any, Throwable, A]): ZIO[Any, Throwable, A] =
    ZIO.acquireReleaseWith(
      ZIO.attemptBlocking(Files.createTempDirectory("graviton-manifest-test-"))
    )(dir =>
      ZIO.attemptBlocking {
        Files
          .walk(dir)
          .sorted(java.util.Comparator.reverseOrder())
          .forEach { path =>
            val _ = Files.deleteIfExists(path)
            ()
          }
      }.orDie
    )(f)
