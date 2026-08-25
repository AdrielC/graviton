package graviton.server

import graviton.core.keys.BinaryKey
import graviton.runtime.config.GravitonConfig
import graviton.runtime.metrics.InMemoryMetricsRegistry
import graviton.runtime.stores.BlobStore
import zio.*
import zio.stream.ZStream
import zio.test.*

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

object DefaultStorageSpec extends ZIOSpecDefault:

  override def spec: Spec[TestEnvironment, Any] =
    suite("default server storage")(
      test("uses a restart-safe filesystem CAS without external services") {
        withTempDir { root =>
          val cfg  = GravitonConfig().copy(fs = GravitonConfig.FsConfig(root = root.toString))
          val data = Chunk.fromArray("server-default-storage".getBytes(StandardCharsets.UTF_8))

          for
            written  <- ingest(cfg, data)
            restored <- retrieve(cfg, written)
          yield assertTrue(
            cfg.blobBackend == "fs",
            restored == data,
          )
        }
      }
    )

  private def ingest(cfg: GravitonConfig, data: Chunk[Byte]): Task[BinaryKey.Blob] =
    (for
      store  <- ZIO.service[BlobStore]
      result <- ZStream.fromChunk(data).run(store.put())
    yield result.key).provide(InMemoryMetricsRegistry.layer, Main.blobLayer(cfg))

  private def retrieve(cfg: GravitonConfig, key: BinaryKey.Blob): Task[Chunk[Byte]] =
    (for
      store <- ZIO.service[BlobStore]
      bytes <- store.get(key).runCollect
    yield bytes).provide(InMemoryMetricsRegistry.layer, Main.blobLayer(cfg))

  private def withTempDir[A](f: Path => Task[A]): Task[A] =
    ZIO.acquireReleaseWith(
      ZIO.attemptBlocking(Files.createTempDirectory("graviton-server-default-"))
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
