package graviton.runtime.stores

import graviton.core.attributes.*
import graviton.core.bytes.*
import graviton.core.keys.*
import graviton.runtime.model.*
import zio.*
import zio.stream.*
import zio.test.*

import java.nio.charset.StandardCharsets

object InMemoryStoresSpec extends ZIOSpecDefault:

  override def spec =
    suite("InMemory stores")(
      test("block store deduplicates and materializes manifest") {
        for
          store  <- InMemoryBlockStore.make
          block  <- canonical("block-a")
          result <- ZStream(block, block).run(store.putBlocks())
          exists <- store.exists(block.key)
          bytes  <- store.get(block.key).runCollect
        yield assertTrue(
          result.manifest.entries.length == 2,
          result.stored.map(_.status) == Chunk(BlockStoredStatus.Fresh, BlockStoredStatus.Duplicate),
          exists,
          bytes == block.bytes,
        )
      },
      test("blob store round-trips bytes and tracks stats") {
        val data = Chunk.fromArray("hello-graviton".getBytes(StandardCharsets.UTF_8))
        for
          store      <- InMemoryBlobStore.make("test-bucket")
          result     <- ZStream.fromChunk(data).run(store.put())
          fetched    <- store.get(result.key).runCollect
          statBefore <- store.stat(result.key)
          _          <- store.delete(result.key)
          statAfter  <- store.stat(result.key)
        yield assertTrue(
          fetched == data,
          statBefore.exists(_.etag.nonEmpty),
          statAfter.isEmpty,
          result.locator.bucket == "test-bucket",
        )
      },
    )

  private def canonical(text: String): IO[Throwable, CanonicalBlock] =
    val bytes = Chunk.fromArray(text.getBytes(StandardCharsets.UTF_8))
    for
      digest <- ZIO
                  .fromEither {
                    for
                      hasher <- Hasher.messageDigest(HashAlgo.default)
                      _       = hasher.update(bytes.toArray)
                      digest <- hasher.result
                    yield digest
                  }
                  .mapError(msg => new IllegalArgumentException(msg))
      bits   <- ZIO
                  .fromEither(KeyBits.create(HashAlgo.default, digest, bytes.length.toLong))
                  .mapError(msg => new IllegalArgumentException(msg))
      key    <- ZIO.fromEither(BinaryKey.block(bits)).mapError(msg => new IllegalArgumentException(msg))
      block  <- ZIO
                  .fromEither(CanonicalBlock.make(key, bytes, BinaryAttributes.empty))
                  .mapError(msg => new IllegalArgumentException(msg))
    yield block
