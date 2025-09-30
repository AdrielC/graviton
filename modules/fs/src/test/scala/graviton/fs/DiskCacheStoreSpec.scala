package graviton.fs

import graviton.*
import zio.*
import zio.stream.*
import zio.test.*
import io.github.iltotore.iron.{zio => _, *}
import io.github.iltotore.iron.constraint.all.*
import java.nio.file.Files

object DiskCacheStoreSpec extends ZIOSpecDefault:

  override def spec =
    suite("DiskCacheStore")(
      test("caches downloads when enabled") {
        for
          dir       <- ZIO.attempt(Files.createTempDirectory("cache-test"))
          store     <- DiskCacheStore.make(dir)
          ref       <- Ref.make(0)
          data       = "hello".getBytes.toIndexedSeq
          hashBytes <- Hashing.compute(
                         Bytes(ZStream.fromIterable(data)),
                         HashAlgorithm.SHA256,
                       )
          digest     = hashBytes.assume[MinLength[16] & MaxLength[64]]
          hash       = Hash(digest, HashAlgorithm.SHA256)
          remote     = ref.updateAndGet(_ + 1).as(Bytes(ZStream.fromIterable(data)))
          _         <- store.fetch(hash, remote, useCache = true)
          _         <- store.fetch(hash, remote, useCache = true)
          calls     <- ref.get
        yield assertTrue(calls == 1)
      },
      test("bypasses cache when disabled") {
        for
          dir       <- ZIO.attempt(Files.createTempDirectory("cache-test"))
          store     <- DiskCacheStore.make(dir)
          ref       <- Ref.make(0)
          data       = "hello".getBytes.toIndexedSeq
          hashBytes <- Hashing.compute(
                         Bytes(ZStream.fromIterable(data)),
                         HashAlgorithm.SHA256,
                       )
          digest     = hashBytes.assume[MinLength[16] & MaxLength[64]]
          hash       = Hash(digest, HashAlgorithm.SHA256)
          remote     = ref.updateAndGet(_ + 1).as(Bytes(ZStream.fromIterable(data)))
          _         <- store.fetch(hash, remote, useCache = false)
          _         <- store.fetch(hash, remote, useCache = false)
          calls     <- ref.get
        yield assertTrue(calls == 2)
      },
    )
