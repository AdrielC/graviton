package graviton.runtime.stores

import graviton.core.attributes.BinaryAttributes
import graviton.core.bytes.Hasher
import graviton.core.keys.{BinaryKey, KeyBits}
import graviton.core.model.Block.*
import graviton.runtime.model.*
import graviton.streams.BoundedByteStream
import zio.*
import zio.test.*

import java.io.IOException
import java.nio.charset.StandardCharsets

object ErasureBlockStoreSpec extends ZIOSpecDefault:
  override def spec: Spec[TestEnvironment, Any] = suite("ErasureBlockStore")(
    test("reconstructs an exact CAS block after any one failure-domain loss") {
      for
        a     <- MemoryFragmentStore.make("a", "zone-a")
        b     <- MemoryFragmentStore.make("b", "zone-b")
        c     <- MemoryFragmentStore.make("c", "zone-c")
        block <- canonical("bounded erasure coding survives one whole target")
        store  = ErasureBlockStore.make(Chunk(a, b, c), preferredFailureDomain = Some("zone-a")).toOption.get
        _     <- store.putBlock(block)
        _     <- b.setAvailable(false)
        bytes <- BoundedByteStream.collectBlock(store.get(block.key))
      yield assertTrue(bytes.bytes == block.bytes)
    },
    test("repairs a missing shard and converges to three healthy copies") {
      for
        a        <- MemoryFragmentStore.make("a", "zone-a")
        b        <- MemoryFragmentStore.make("b", "zone-b")
        c        <- MemoryFragmentStore.make("c", "zone-c")
        block    <- canonical("repair this erasure shard")
        store     = ErasureBlockStore.make(Chunk(a, b, c)).toOption.get
        _        <- store.putBlock(block)
        _        <- b.clear
        report   <- store.converge(block.key)
        restored <- b.contains(block.key, 1)
        bytes    <- BoundedByteStream.collectBlock(store.get(block.key))
      yield assertTrue(report.repairedCopies == 1, report.failedCopies.isEmpty, restored, bytes.bytes == block.bytes)
    },
    test("refuses configurations without three independent failure domains") {
      for
        a <- MemoryFragmentStore.make("a", "zone-a")
        b <- MemoryFragmentStore.make("b", "zone-a")
        c <- MemoryFragmentStore.make("c", "zone-c")
      yield assertTrue(ErasureBlockStore.make(Chunk(a, b, c)).isLeft)
    },
    test("keeps every shard bounded at the 16 MiB canonical-block ceiling") {
      val source = Array.tabulate[Byte](16 * 1024 * 1024)(index => (index % 251).toByte)
      for
        block     <- canonical(Chunk.fromArray(source))
        fragments <- Xor21Codec.encode(block)
        decoded   <- Xor21Codec.decode(block.key, Map(0 -> fragments(0), 2 -> fragments(2)))
      yield assertTrue(
        fragments.forall(_.chunk.length == ErasureFragmentBytes.maxBytes),
        decoded.key == block.key,
        decoded.bytes == block.bytes,
      )
    },
    test("rejects an oversized declared block before asking any shard store for bytes") {
      for
        a      <- MemoryFragmentStore.make("a", "zone-a")
        b      <- MemoryFragmentStore.make("b", "zone-b")
        c      <- MemoryFragmentStore.make("c", "zone-c")
        seed   <- canonical("oversized-key-seed")
        bits   <- ZIO
                    .fromEither(KeyBits.create(seed.key.bits.algo, seed.key.bits.digest, 16777217L))
                    .mapError(new IllegalArgumentException(_))
        key    <- ZIO.fromEither(BinaryKey.block(bits)).mapError(new IllegalArgumentException(_))
        store   = ErasureBlockStore.make(Chunk(a, b, c)).toOption.get
        exit   <- store.get(key).runDrain.exit
        aReads <- a.readCount
        bReads <- b.readCount
        cReads <- c.readCount
      yield assertTrue(exit.isFailure, aReads == 0, bReads == 0, cReads == 0)
    },
    test("does not wait for a hung preferred domain when two remote shards are healthy") {
      for
        a     <- MemoryFragmentStore.make("a", "zone-a")
        b     <- MemoryFragmentStore.make("b", "zone-b")
        c     <- MemoryFragmentStore.make("c", "zone-c")
        block <- canonical("remote quorum must outrun a hung local endpoint")
        hungA  = new ErasureFragmentStore:
                   override val name: String                                                                                = a.name
                   override val failureDomain: String                                                                       = a.failureDomain
                   override def put(key: BinaryKey.Block, fragment: ErasureFragment): IO[StoreError, BlockStoredStatus]     =
                     a.put(key, fragment)
                   override def get(key: BinaryKey.Block, index: Int, expectedLength: Int): IO[StoreError, ErasureFragment] =
                     ZIO.never
                   override def repair(key: BinaryKey.Block, fragment: ErasureFragment): IO[StoreError, Unit]               =
                     a.repair(key, fragment)
                   override def healthCheck: IO[StoreError, Unit]                                                           = a.healthCheck
        store  = ErasureBlockStore.make(Chunk(hungA, b, c), preferredFailureDomain = Some("zone-a")).toOption.get
        _     <- store.putBlock(block)
        bytes <- Live.live(
                   BoundedByteStream
                     .collectBlock(store.get(block.key))
                     .timeoutFail(new IOException("remote erasure quorum timed out"))(2.seconds)
                 )
      yield assertTrue(bytes.bytes == block.bytes)
    },
  )

  private final class MemoryFragmentStore private (
    val name: String,
    val failureDomain: String,
    state: Ref[Map[(String, Int), ErasureFragment]],
    available: Ref[Boolean],
    reads: Ref[Int],
  ) extends ErasureFragmentStore:
    override def put(key: BinaryKey.Block, fragment: ErasureFragment): IO[StoreError, BlockStoredStatus] =
      ensureAvailable *> state.modify { current =>
        val locator = key.bits.render -> fragment.index
        if current.contains(locator) then BlockStoredStatus.Duplicate -> current
        else BlockStoredStatus.Fresh                                  -> current.updated(locator, fragment)
      }

    override def get(key: BinaryKey.Block, index: Int, expectedLength: Int): IO[StoreError, ErasureFragment] =
      ensureAvailable *> reads.update(_ + 1) *> state.get.flatMap(values =>
        ZIO
          .fromOption(values.get(key.bits.render -> index))
          .orElseFail(StoreError.NotFound(StoreOperation.GetBlock, key))
          .filterOrFail(_.chunk.length == expectedLength)(
            StoreError.CorruptData(StoreOperation.GetBlock, s"shard $index has the wrong length")
          )
      )

    override def repair(key: BinaryKey.Block, fragment: ErasureFragment): IO[StoreError, Unit] =
      ensureAvailable *> state.update(_.updated(key.bits.render -> fragment.index, fragment))

    override def healthCheck: IO[StoreError, Unit] = ensureAvailable

    def setAvailable(value: Boolean): UIO[Unit]                  = available.set(value)
    def clear: UIO[Unit]                                         = state.set(Map.empty)
    def contains(key: BinaryKey.Block, index: Int): UIO[Boolean] = state.get.map(_.contains(key.bits.render -> index))
    def readCount: UIO[Int]                                      = reads.get

    private def ensureAvailable: IO[StoreError, Unit] =
      available.get.flatMap(value =>
        ZIO
          .fail(StoreError.Unavailable(StoreOperation.HealthCheck, StoreBackend.InMemory, new IOException(s"$name unavailable")))
          .unless(value)
          .unit
      )

  private object MemoryFragmentStore:
    def make(name: String, failureDomain: String): UIO[MemoryFragmentStore] =
      for
        state     <- Ref.make(Map.empty[(String, Int), ErasureFragment])
        available <- Ref.make(true)
        reads     <- Ref.make(0)
      yield new MemoryFragmentStore(name, failureDomain, state, available, reads)

  private def canonical(value: String): Task[CanonicalBlock] =
    canonical(Chunk.fromArray(value.getBytes(StandardCharsets.UTF_8)))

  private def canonical(bytes: Chunk[Byte]): Task[CanonicalBlock] =
    for
      hasher <- ZIO.fromEither(Hasher.systemDefault).mapError(new IllegalArgumentException(_))
      _      <- ZIO.attempt(hasher.update(bytes.toArray))
      digest <- ZIO.fromEither(hasher.digest).mapError(new IllegalArgumentException(_))
      bits   <- ZIO.fromEither(KeyBits.create(hasher.algo, digest, bytes.length.toLong)).mapError(new IllegalArgumentException(_))
      key    <- ZIO.fromEither(BinaryKey.block(bits)).mapError(new IllegalArgumentException(_))
      block  <- ZIO.fromEither(CanonicalBlock.make(key, bytes, BinaryAttributes.empty)).mapError(new IllegalArgumentException(_))
    yield block
