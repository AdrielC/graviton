package graviton.runtime.streaming

import graviton.core.attributes.BinaryAttributes
import graviton.core.bytes.Hasher
import graviton.core.keys.{BinaryKey, KeyBits}
import graviton.runtime.model.CanonicalBlock
import graviton.runtime.stores.InMemoryBlockStore
import zio.*
import zio.stream.ZStream
import zio.test.*
import zio.test.TestClock

import java.nio.charset.StandardCharsets

object BlobStreamerSpec extends ZIOSpecDefault:

  private def canonical(text: String): IO[Throwable, CanonicalBlock] =
    val bytes = Chunk.fromArray(text.getBytes(StandardCharsets.UTF_8))
    for
      hasher <- ZIO.fromEither(Hasher.systemDefault).mapError(err => new IllegalStateException(err))
      algo    = hasher.algo
      _       = hasher.update(bytes.toArray)
      digest <- ZIO.fromEither(hasher.digest).mapError(msg => new IllegalArgumentException(msg))
      bits   <- ZIO.fromEither(KeyBits.create(algo, digest, bytes.length.toLong)).mapError(msg => new IllegalArgumentException(msg))
      key    <- ZIO.fromEither(BinaryKey.block(bits)).mapError(msg => new IllegalArgumentException(msg))
      block  <- ZIO.fromEither(CanonicalBlock.make(key, bytes, BinaryAttributes.empty)).mapError(msg => new IllegalArgumentException(msg))
    yield block

  override def spec: Spec[TestEnvironment, Any] =
    suite("BlobStreamer")(
      test("streams bytes in manifest order with bounded parallel fetch") {
        for
          store <- InMemoryBlockStore.make
          b0    <- canonical("A" * 1024)
          b1    <- canonical("B" * 1024)
          b2    <- canonical("C" * 1024)
          b3    <- canonical("D" * 1024)
          _     <- ZStream(b0, b1, b2, b3).run(store.putBlocks()).unit

          refs = ZStream(
                   BlobStreamer.BlockRef(0L, b0.key),
                   BlobStreamer.BlockRef(1L, b1.key),
                   BlobStreamer.BlockRef(2L, b2.key),
                   BlobStreamer.BlockRef(3L, b3.key),
                 )

          // Inject a delay into block 0 to force out-of-order completion under parallelism.
          delayedStore = new graviton.runtime.stores.BlockStore {
                           override def putBlocks(plan: graviton.runtime.model.BlockWritePlan) = store.putBlocks(plan)
                           override def exists(key: BinaryKey.Block)                           = store.exists(key)
                           override def get(key: BinaryKey.Block)                              =
                             (if key == b0.key then ZStream.fromZIO(TestClock.adjust(200.millis)).drain else ZStream.empty) ++
                               store.get(key)
                         }

          bytes   <- BlobStreamer
                       .streamBlob(refs, delayedStore, BlobStreamer.Config(windowRefs = 2, maxInFlight = 2))
                       .runCollect
          expected = b0.bytes ++ b1.bytes ++ b2.bytes ++ b3.bytes
          s0       = bytes.take(b0.bytes.length)
          s1       = bytes.slice(b0.bytes.length, b0.bytes.length + b1.bytes.length)
          s2       = bytes.slice(b0.bytes.length + b1.bytes.length, b0.bytes.length + b1.bytes.length + b2.bytes.length)
          s3       = bytes.drop(b0.bytes.length + b1.bytes.length + b2.bytes.length)
        yield assertTrue(
          bytes.length == expected.length,
          s0 == b0.bytes,
          s1 == b1.bytes,
          s2 == b2.bytes,
          s3 == b3.bytes,
        )
      }
    )
