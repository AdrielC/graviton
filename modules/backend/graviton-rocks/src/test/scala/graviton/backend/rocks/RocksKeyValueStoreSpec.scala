package graviton.backend.rocks

import graviton.runtime.kv.{KeyValueStore, KvKey, KvValue}
import zio.*
import zio.test.*

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.util.Comparator

object RocksKeyValueStoreSpec extends ZIOSpecDefault:

  override def spec: Spec[TestEnvironment, Any] =
    suite("RocksKeyValueStore")(
      test("put/get/delete round-trips bytes") {
        val key   = KvKey.applyUnsafe("k1")
        val value = KvValue.fromArray("hello-rocks".getBytes(StandardCharsets.UTF_8)).toOption.get
        withTempDir { dir =>
          ZIO.scoped {
            for
              store <- RocksKeyValueStore.open(dir)
              _     <- store.put(key, value)
              got   <- store.get(key)
              _     <- store.delete(key)
              after <- store.get(key)
            yield assertTrue(
              got.contains(value),
              after.isEmpty,
            )
          }
        }
      },
      test("layer wires KeyValueStore service") {
        val key   = KvKey.applyUnsafe("k2")
        val value = KvValue.fromArray("layer-value".getBytes(StandardCharsets.UTF_8)).toOption.get
        withTempDir { dir =>
          (for
            store <- ZIO.service[KeyValueStore]
            _     <- store.put(key, value)
            got   <- store.get(key)
          yield assertTrue(got.contains(value)))
            .provideLayer(RocksLayers.live(dir))
        }
      },
      test("data survives closing and reopening the database") {
        val key   = KvKey.applyUnsafe("durable")
        val value = KvValue.fromArray("restart-safe".getBytes(StandardCharsets.UTF_8)).toOption.get
        withTempDir { dir =>
          for
            _   <- ZIO.scoped(RocksKeyValueStore.open(dir).flatMap(_.put(key, value)))
            got <- ZIO.scoped(RocksKeyValueStore.open(dir).flatMap(_.get(key)))
          yield assertTrue(got.contains(value))
        }
      },
    )

  private def withTempDir[A](use: Path => ZIO[Any, Throwable, A]): ZIO[Any, Throwable, A] =
    ZIO.acquireReleaseWith(
      ZIO.attemptBlocking(Files.createTempDirectory("graviton-rocks-spec-"))
    )(dir => deleteRecursively(dir).ignore)(use)

  private def deleteRecursively(dir: Path): Task[Unit] =
    ZIO.attemptBlocking {
      if Files.exists(dir) then
        val stream = Files.walk(dir)
        try
          stream
            .sorted(Comparator.reverseOrder())
            .forEach { path =>
              Files.deleteIfExists(path); ()
            }
        finally stream.close()
    }
