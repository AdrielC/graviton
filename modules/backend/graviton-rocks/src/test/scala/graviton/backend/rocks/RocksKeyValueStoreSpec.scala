package graviton.backend.rocks

import graviton.runtime.kv.KeyValueStore
import zio.*
import zio.test.*

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.util.Comparator

object RocksKeyValueStoreSpec extends ZIOSpecDefault:

  override def spec: Spec[TestEnvironment, Any] =
    suite("RocksKeyValueStore")(
      test("put/get/delete round-trips bytes") {
        val value = "hello-rocks".getBytes(StandardCharsets.UTF_8)
        withTempDir { dir =>
          ZIO.scoped {
            for
              store <- RocksKeyValueStore.open(dir)
              _     <- store.put("k1", value)
              got   <- store.get("k1")
              _     <- store.delete("k1")
              after <- store.get("k1")
            yield assertTrue(
              got.exists(bytes => java.util.Arrays.equals(bytes, value)),
              after.isEmpty,
            )
          }
        }
      },
      test("layer wires KeyValueStore service") {
        val value = "layer-value".getBytes(StandardCharsets.UTF_8)
        withTempDir { dir =>
          ZIO.scoped {
            (for
              store <- ZIO.service[KeyValueStore]
              _     <- store.put("k2", value)
              got   <- store.get("k2")
            yield assertTrue(got.exists(bytes => java.util.Arrays.equals(bytes, value))))
              .provideSomeLayer[Scope](RocksLayers.live(dir))
          }
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
            .forEach(path => Files.deleteIfExists(path))
        finally stream.close()
    }
