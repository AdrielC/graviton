package graviton.objectstore.filesystem

import graviton.objectstore.*
import graviton.ranges.ByteRange
import zio.*
import zio.stream.*
import zio.test.*
import zio.Chunk
import java.nio.file.{Files, Path}

object FileSystemObjectStoreSpec extends ZIOSpecDefault:
  def spec = suite("FileSystemObjectStore")(
    test("put/get/delete round-trip") {
      ZIO.scoped {
        temporaryDirectory.flatMap { dir =>
          for
            store  <- FileSystemObjectStore.make(dir)
            path    = ObjectPath("foo/bar.bin")
            data    = Chunk.fromArray("hello".getBytes(java.nio.charset.StandardCharsets.UTF_8))
            _      <- store.put(path, ZStream.fromChunk(data))
            read   <- store.get(path).runCollect
            _      <- store.delete(path)
            exists <- store.head(path)
          yield assertTrue(read == data, exists.isEmpty)
        }
      }
    },
    test("range requests respect provided bounds") {
      ZIO.scoped {
        temporaryDirectory.flatMap { dir =>
          for
            store <- FileSystemObjectStore.make(dir)
            path   = ObjectPath("blob.bin")
            data   = Chunk.fromArray("abcdef".getBytes(java.nio.charset.StandardCharsets.UTF_8))
            _     <- store.put(path, ZStream.fromChunk(data))
            bytes <- store.get(path, Some(ByteRange(1L, 4L))).runCollect
          yield assertTrue(bytes == Chunk.fromArray("bcd".getBytes(java.nio.charset.StandardCharsets.UTF_8)))
        }
      }
    },
  )

  private def temporaryDirectory: ZIO[Scope, Nothing, Path] =
    val acquire = ZIO.attempt(Files.createTempDirectory("fs-object-store")).mapError(ObjectStoreError.fromThrowable)
    ZIO.acquireRelease(acquire.orDie)(dir => ZIO.attempt(deleteRecursively(dir)).ignore)

  private def deleteRecursively(path: Path): Unit =
    Files
      .walk(path)
      .sorted(java.util.Comparator.reverseOrder())
      .forEach { p =>
        Files.deleteIfExists(p); ()
      }
