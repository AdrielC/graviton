package graviton.fs

import graviton.*
import zio.*
import zio.stream.*
import zio.test.*
import java.nio.file.Files
import org.testcontainers.containers.GenericContainer
import org.testcontainers.utility.DockerImageName
import zio.test.TestAspect

import graviton.domain.HashBytes

class HttpEchoContainer
    extends GenericContainer[HttpEchoContainer](
      DockerImageName.parse("hashicorp/http-echo:1.0")
    )

object RocksDBCacheStoreSpec extends ZIOSpecDefault:

  override def spec =
    suite("RocksDBCacheStore")(
      test("caches downloads when enabled") {
        for
          dir       <- ZIO.attempt(Files.createTempDirectory("rocks-cache-test"))
          store     <- RocksDBCacheStore.make(dir)
          ref       <- Ref.make(0)
          data       = "hello".getBytes.toIndexedSeq
          hashBytes <- Hashing.compute(
                         Bytes(ZStream.fromIterable(data)),
                         HashAlgorithm.SHA256,
                       )
          digest     <- ZIO.fromEither(HashBytes.either(hashBytes)).mapError(e => new RuntimeException(e))
          hash       = Hash(digest, HashAlgorithm.SHA256)
          remote     = ref.updateAndGet(_ + 1).as(Bytes(ZStream.fromIterable(data)))
          _         <- store.fetch(hash, remote, useCache = true)
          _         <- store.fetch(hash, remote, useCache = true)
          calls     <- ref.get
        yield assertTrue(calls == 1)
      },
      test("bypasses cache when disabled") {
        for
          dir       <- ZIO.attempt(Files.createTempDirectory("rocks-cache-test"))
          store     <- RocksDBCacheStore.make(dir)
          ref       <- Ref.make(0)
          data       = "hello".getBytes.toIndexedSeq
          hashBytes <- Hashing.compute(
                         Bytes(ZStream.fromIterable(data)),
                         HashAlgorithm.SHA256,
                       )
          digest     <- ZIO.fromEither(HashBytes.either(hashBytes)).mapError(e => new RuntimeException(e))
          hash       = Hash(digest, HashAlgorithm.SHA256)
          remote     = ref.updateAndGet(_ + 1).as(Bytes(ZStream.fromIterable(data)))
          _         <- store.fetch(hash, remote, useCache = false)
          _         <- store.fetch(hash, remote, useCache = false)
          calls     <- ref.get
        yield assertTrue(calls == 2)
      },
      test("caches downloads from external service") {
        val acquire = ZIO.attempt {
          val container = new HttpEchoContainer()
            .withCommand("-text=hello")
            .withExposedPorts(5678)
          container.start()
          container
        }
        val release = (c: GenericContainer[?]) => ZIO.attempt(c.stop()).ignore
        ZIO.acquireRelease(acquire)(release).flatMap { c =>
          val port                         = c.getMappedPort(5678)
          val host                         = c.getHost
          def fetchOnce: Task[Array[Byte]] =
            ZIO.attemptBlockingIO {
              val url = java.net.URI.create(s"http://$host:$port/").toURL
              val in  = url.openStream()
              try in.readAllBytes()
              finally in.close()
            }
          for
            dir       <- ZIO.attempt(Files.createTempDirectory("rocks-http-test"))
            arr0      <- fetchOnce
            hashBytes <- Hashing.compute(
                           Bytes(ZStream.fromIterable(arr0)),
                           HashAlgorithm.SHA256,
                         )
            digest     <- ZIO.fromEither(HashBytes.either(hashBytes)).mapError(e => new RuntimeException(e))
            hash       = Hash(digest, HashAlgorithm.SHA256)
            ref       <- Ref.make(0)
            remote     = ref.updateAndGet(_ + 1) *> fetchOnce
                           .map(arr => Bytes(ZStream.fromIterable(arr)))
            store     <- RocksDBCacheStore.make(dir)
            _         <- store.fetch(hash, remote, useCache = true)
            _         <- store.fetch(hash, remote, useCache = true)
            calls     <- ref.get
          yield assertTrue(calls == 1)
        }
      } @@ TestAspect.ifEnv("TESTCONTAINERS") { value =>
        value.trim match
          case v if v.equalsIgnoreCase("1") || v.equalsIgnoreCase("true") || v.equalsIgnoreCase("yes") => true
          case _                                                                                       => false
      },
    )
