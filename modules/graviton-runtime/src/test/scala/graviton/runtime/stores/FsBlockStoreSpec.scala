package graviton.runtime.stores

import graviton.core.attributes.BinaryAttributes
import graviton.core.bytes.*
import graviton.core.keys.*
import graviton.runtime.model.*
import zio.*
import zio.stream.*
import zio.test.*

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

object FsBlockStoreSpec extends ZIOSpecDefault:

  override def spec: Spec[TestEnvironment, Any] =
    suite("FsBlockStore")(
      test("put and get round-trips block bytes") {
        withTempDir { root =>
          for
            store <- ZIO.succeed(new FsBlockStore(root))
            block <- canonical("round-trip-test-data")
            _     <- ZStream(block).run(store.putBlocks())
            bytes <- store.get(block.key).runCollect
          yield assertTrue(bytes == block.bytes)
        }
      },
      test("exists returns true for stored blocks and false for missing") {
        withTempDir { root =>
          for
            store  <- ZIO.succeed(new FsBlockStore(root))
            block  <- canonical("exists-check")
            before <- store.exists(block.key)
            _      <- ZStream(block).run(store.putBlocks())
            after  <- store.exists(block.key)
          yield assertTrue(!before, after)
        }
      },
      test("deduplicates identical blocks") {
        withTempDir { root =>
          for
            store  <- ZIO.succeed(new FsBlockStore(root))
            block  <- canonical("dedup-me")
            result <- ZStream(block, block, block).run(store.putBlocks())
          yield assertTrue(
            result.manifest.entries.length == 3,
            result.stored.count(_.status == BlockStoredStatus.Fresh) == 1,
            result.stored.count(_.status == BlockStoredStatus.Duplicate) == 2,
          )
        }
      },
      test("builds manifest with correct offsets") {
        withTempDir { root =>
          for
            store <- ZIO.succeed(new FsBlockStore(root))
            b0    <- canonical("block-alpha")
            b1    <- canonical("block-bravo")
            b2    <- canonical("block-charlie")

            result <- ZStream(b0, b1, b2).run(store.putBlocks())
            entries = result.manifest.entries
          yield assertTrue(
            entries.length == 3,
            entries(0).offset.value == 0L,
            entries(1).offset.value == b0.size.value.toLong,
            entries(2).offset.value == b0.size.value.toLong + b1.size.value.toLong,
          )
        }
      },
      test("stores blocks in algo-based directory structure") {
        withTempDir { root =>
          for
            store  <- ZIO.succeed(new FsBlockStore(root))
            block  <- canonical("layout-test")
            _      <- ZStream(block).run(store.putBlocks())
            // The path should be: root/cas/blocks/<algo>/<hex>-<size>
            algo    = block.key.bits.algo match
                        case HashAlgo.Sha256 => "sha256"
                        case HashAlgo.Blake3 => "blake3"
                        case other           => other.primaryName
            hex     = block.key.bits.digest.hex.value
            name    = s"$hex-${block.key.bits.size}"
            path    = root.resolve("cas/blocks").resolve(algo).resolve(name)
            exists <- ZIO.attemptBlocking(Files.exists(path))
          yield assertTrue(exists)
        }
      },
      test("handles concurrent puts of same block safely") {
        withTempDir { root =>
          for
            store   <- ZIO.succeed(new FsBlockStore(root))
            block   <- canonical("concurrent-block")
            // Run 5 concurrent puts of the same block
            results <- ZIO.foreachPar(1 to 5)(_ => ZStream(block).run(store.putBlocks()))
            // All should succeed (atomic move handles races)
            bytes   <- store.get(block.key).runCollect
          yield assertTrue(
            results.length == 5,
            bytes == block.bytes,
          )
        }
      },
      test("multiple distinct blocks stored and retrievable") {
        withTempDir { root =>
          for
            store        <- ZIO.succeed(new FsBlockStore(root))
            blocks       <- ZIO.foreach((1 to 10).toList)(i => canonical(s"block-$i"))
            _            <- ZStream.fromIterable(blocks).run(store.putBlocks())
            roundTripped <- ZIO.foreach(blocks) { block =>
                              store.get(block.key).runCollect.map(b => block.bytes == b)
                            }
          yield assertTrue(roundTripped.forall(identity))
        }
      },
    )

  private def canonical(text: String): IO[Throwable, CanonicalBlock] =
    val bytes = Chunk.fromArray(text.getBytes(StandardCharsets.UTF_8))
    for
      hasher <- ZIO
                  .fromEither(Hasher.systemDefault)
                  .mapError(err => new IllegalStateException(err))
      algo    = hasher.algo
      _       = hasher.update(bytes.toArray)
      digest <- ZIO.fromEither(hasher.digest).mapError(msg => new IllegalArgumentException(msg))
      bits   <- ZIO
                  .fromEither(KeyBits.create(algo, digest, bytes.length.toLong))
                  .mapError(msg => new IllegalArgumentException(msg))
      key    <- ZIO.fromEither(BinaryKey.block(bits)).mapError(msg => new IllegalArgumentException(msg))
      block  <- ZIO
                  .fromEither(CanonicalBlock.make(key, bytes, BinaryAttributes.empty))
                  .mapError(msg => new IllegalArgumentException(msg))
    yield block

  private def withTempDir[A](f: Path => ZIO[Any, Throwable, A]): ZIO[Any, Throwable, A] =
    ZIO.acquireReleaseWith(
      ZIO.attemptBlocking(Files.createTempDirectory("graviton-fs-test-"))
    )(dir =>
      ZIO.attemptBlocking {
        // Recursively delete temp directory
        java.nio.file.Files
          .walk(dir)
          .sorted(java.util.Comparator.reverseOrder())
          .forEach { p =>
            val _ = java.nio.file.Files.deleteIfExists(p)
          }
      }.orDie
    )(f)
