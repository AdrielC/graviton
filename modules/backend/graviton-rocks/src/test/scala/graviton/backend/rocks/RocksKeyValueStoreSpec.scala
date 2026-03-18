package graviton.backend.rocks

import zio.*
import zio.test.*

import java.nio.file.{Files, Path}

object RocksKeyValueStoreSpec extends ZIOSpecDefault:

  override def spec: Spec[TestEnvironment, Any] =
    suite("RocksKeyValueStore")(
      test("put and get round-trips a value") {
        withTempDb { store =>
          for
            _      <- store.put("key1", "hello".getBytes("UTF-8"))
            result <- store.get("key1")
          yield assertTrue(result.map(new String(_, "UTF-8")).contains("hello"))
        }
      },
      test("get returns None for a missing key") {
        withTempDb { store =>
          for result <- store.get("no-such-key")
          yield assertTrue(result.isEmpty)
        }
      },
      test("put overwrites an existing value") {
        withTempDb { store =>
          for
            _      <- store.put("overwrite", "first".getBytes("UTF-8"))
            _      <- store.put("overwrite", "second".getBytes("UTF-8"))
            result <- store.get("overwrite")
          yield assertTrue(result.map(new String(_, "UTF-8")).contains("second"))
        }
      },
      test("delete removes a key") {
        withTempDb { store =>
          for
            _      <- store.put("to-delete", "value".getBytes("UTF-8"))
            before <- store.get("to-delete")
            _      <- store.delete("to-delete")
            after  <- store.get("to-delete")
          yield assertTrue(before.isDefined, after.isEmpty)
        }
      },
      test("delete is idempotent for missing keys") {
        withTempDb { store =>
          for result <- store.delete("ghost").exit
          yield assertTrue(result.isSuccess)
        }
      },
      test("multiple distinct keys are stored independently") {
        withTempDb { store =>
          val pairs = (1 to 10).map(i => s"key-$i" -> s"value-$i")
          for
            _       <- ZIO.foreach(pairs) { case (k, v) => store.put(k, v.getBytes("UTF-8")) }
            results <- ZIO.foreach(pairs) { case (k, v) =>
                         store.get(k).map(r => r.map(new String(_, "UTF-8")).contains(v))
                       }
          yield assertTrue(results.forall(identity))
        }
      },
      test("stores and retrieves binary (non-UTF-8) bytes") {
        withTempDb { store =>
          val blob = Array[Byte](0, 1, 127, -1, -128)
          for
            _      <- store.put("binary", blob)
            result <- store.get("binary")
          yield assertTrue(result.exists(java.util.Arrays.equals(_, blob)))
        }
      },
    )

  private def withTempDb[A](f: RocksKeyValueStore => ZIO[Any, Throwable, A]): ZIO[Any, Throwable, A] =
    ZIO.acquireReleaseWith(
      ZIO.attemptBlocking(Files.createTempDirectory("graviton-rocks-test-"))
    )(dir =>
      ZIO.attemptBlocking {
        java.nio.file.Files
          .walk(dir)
          .sorted(java.util.Comparator.reverseOrder())
          .forEach { p =>
            val _ = java.nio.file.Files.deleteIfExists(p)
          }
      }.orDie
    ) { dir =>
      ZIO.scoped(RocksKeyValueStore.open(dir).flatMap(f))
    }
