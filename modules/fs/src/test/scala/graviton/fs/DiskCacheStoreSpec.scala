package graviton.fs

import graviton.*
import zio.*
import zio.stream.*
import zio.test.*
import java.nio.file.Files
import graviton.Hash

object DiskCacheStoreSpec extends ZIOSpecDefault:

  override def spec =
    suite("DiskCacheStore")(
      test("caches downloads when enabled") {
        for
          dir       <- ZIO.acquireRelease(ZIO.attempt(Files.createTempDirectory("cache-test-1")))(
            dir => ZIO.attempt(Files.deleteIfExists(dir)).ignore
          )
          store     <- DiskCacheStore.make(dir)
          ref       <- Ref.make(0)
          data       = "hello"
          hash       <- Hashing.compute(Bytes(data))
          remote     = ref.updateAndGet(_ + 1).as(Bytes(data))
          _         <- store.fetch(Hash.SingleHash(hash.bytes.head._1, hash.bytes.head._2), remote, useCache = true)
          _         <- store.fetch(Hash.SingleHash(hash.bytes.head._1, hash.bytes.head._2), remote, useCache = true)
          calls     <- ref.get
        yield assertTrue(calls == 1)
      },
      test("bypasses cache when disabled") {
        for
          dir       <- ZIO.acquireRelease(ZIO.attempt(Files.createTempDirectory("cache-test-2")))(
            dir => ZIO.attempt(Files.deleteIfExists(dir)).ignore
          )
          store     <- DiskCacheStore.make(dir)
          ref       <- Ref.make(0)
          data       = "hello".getBytes.toIndexedSeq
          hash <- Hashing.compute(Bytes(ZStream.fromIterable(data)))
          remote     = ref.updateAndGet(_ + 1).as(Bytes(ZStream.fromIterable(data)))
          _         <- store.fetch(Hash.SingleHash(hash.bytes.head._1, hash.bytes.head._2), remote, useCache = false)
          _         <- store.fetch(Hash.SingleHash(hash.bytes.head._1, hash.bytes.head._2), remote, useCache = false)
          calls     <- ref.get
        yield assertTrue(calls == 2)
      },
    )
