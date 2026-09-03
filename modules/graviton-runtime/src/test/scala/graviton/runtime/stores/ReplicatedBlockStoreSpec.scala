package graviton.runtime.stores

import graviton.core.attributes.BinaryAttributes
import graviton.core.bytes.Hasher
import graviton.core.keys.{BinaryKey, KeyBits}
import graviton.core.model.Block.*
import graviton.runtime.model.*
import graviton.streams.BoundedByteStream
import zio.*
import zio.stream.*
import zio.test.*
import zio.test.Assertion.*

import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicInteger

object ReplicatedBlockStoreSpec extends ZIOSpecDefault:

  override def spec: Spec[TestEnvironment, Any] = suite("ReplicatedBlockStore")(
    test("commits after the configured write quorum") {
      for
        first  <- InMemoryBlockStore.make
        second <- InMemoryBlockStore.make
        block  <- canonical("quorum-write")
        store   = ReplicatedBlockStore
                    .make(
                      Chunk(
                        ReplicatedBlockStore.Replica("first", first),
                        ReplicatedBlockStore.Replica("second", second),
                        ReplicatedBlockStore.Replica("down", FailingStore),
                      ),
                      writeQuorum = 2,
                    )
                    .toOption
                    .get
        result <- ZStream.succeed(block).run(store.putBlocks())
        a      <- first.exists(block.key)
        b      <- second.exists(block.key)
      yield assertTrue(result.stored.head.status == BlockStoredStatus.Fresh, a, b)
    },
    test("fails when write quorum cannot be reached") {
      for
        healthy <- InMemoryBlockStore.make
        block   <- canonical("quorum-failure")
        store    = ReplicatedBlockStore
                     .make(
                       Chunk(
                         ReplicatedBlockStore.Replica("healthy", healthy),
                         ReplicatedBlockStore.Replica("down-a", FailingStore),
                         ReplicatedBlockStore.Replica("down-b", FailingStore),
                       ),
                       writeQuorum = 2,
                     )
                     .toOption
                     .get
        exit    <- ZStream.succeed(block).run(store.putBlocks()).exit
      yield assert(exit)(fails(isSubtype[StoreError.QuorumUnavailable](anything)))
    },
    test("falls back to a valid copy and repairs a missing replica") {
      for
        missing  <- InMemoryBlockStore.make
        source   <- InMemoryBlockStore.make
        block    <- canonical("read-repair")
        _        <- ZStream.succeed(block).run(source.putBlocks())
        store     = ReplicatedBlockStore
                      .make(
                        Chunk(
                          ReplicatedBlockStore.Replica("missing", missing),
                          ReplicatedBlockStore.Replica("source", source),
                        ),
                        writeQuorum = 1,
                      )
                      .toOption
                      .get
        report   <- store.repair(block.key)
        bytes    <- store.get(block.key).runCollect
        repaired <- missing.exists(block.key)
      yield assertTrue(bytes == block.bytes, repaired, report.repairedReplicas == 1)
    },
    test("rendezvous placement is stable and spreads across failure domains") {
      for
        first   <- InMemoryBlockStore.make
        second  <- InMemoryBlockStore.make
        third   <- InMemoryBlockStore.make
        block   <- canonical("stable-placement")
        replicas = Chunk(
                     ReplicatedBlockStore.Replica("rack-a-1", "rack-a", first),
                     ReplicatedBlockStore.Replica("rack-a-2", "rack-a", second),
                     ReplicatedBlockStore.Replica("rack-b-1", "rack-b", third),
                   )
        one     <- ReplicaPlacement.rendezvous.select(block.key, replicas, desiredReplicas = 2)
        two     <- ReplicaPlacement.rendezvous.select(block.key, replicas, desiredReplicas = 2)
      yield assertTrue(
        one.map(_.name) == two.map(_.name),
        one.length == 2,
        one.map(_.failureDomain).distinct.length == 2,
      )
    },
    test("topology expansion migrates a block from an old configured target") {
      val oldPlacement = new ReplicaPlacement:
        override def select(
          key: BinaryKey.Block,
          candidates: Chunk[ReplicatedBlockStore.Replica],
          desiredReplicas: Int,
        ): UIO[Chunk[ReplicatedBlockStore.Replica]] = ZIO.succeed(candidates.takeRight(desiredReplicas))
      val newPlacement = new ReplicaPlacement:
        override def select(
          key: BinaryKey.Block,
          candidates: Chunk[ReplicatedBlockStore.Replica],
          desiredReplicas: Int,
        ): UIO[Chunk[ReplicatedBlockStore.Replica]] = ZIO.succeed(candidates.take(desiredReplicas))

      for
        first   <- InMemoryBlockStore.make
        second  <- InMemoryBlockStore.make
        old     <- InMemoryBlockStore.make
        block   <- canonical("expanded-topology")
        replicas = Chunk(
                     ReplicatedBlockStore.Replica("new-a", first),
                     ReplicatedBlockStore.Replica("new-b", second),
                     ReplicatedBlockStore.Replica("old", old),
                   )
        oldStore = ReplicatedBlockStore.make(replicas, 1, 1, oldPlacement).toOption.get
        _       <- oldStore.putBlock(block)
        newStore = ReplicatedBlockStore.make(replicas, 2, 2, newPlacement).toOption.get
        report  <- newStore.repair(block.key)
        a       <- first.exists(block.key)
        b       <- second.exists(block.key)
        bytes   <- newStore.get(block.key).runCollect
      yield assertTrue(report.repairedReplicas == 2, a, b, bytes == block.bytes)
    },
    test("tries a validated local failure-domain copy before a remote candidate") {
      val remoteReads = new AtomicInteger(0)
      val remote      = new CountingFailingStore(remoteReads)
      for
        local <- InMemoryBlockStore.make
        block <- canonical("local-read-preference")
        _     <- local.putBlock(block)
        store  = ReplicatedBlockStore
                   .make(
                     Chunk(
                       ReplicatedBlockStore.Replica("remote", "zone-b", remote),
                       ReplicatedBlockStore.Replica("local", "zone-a", local),
                     ),
                     desiredReplicas = 2,
                     writeQuorum = 1,
                     placement = ReplicaPlacement.rendezvous,
                     metrics = graviton.runtime.metrics.MetricsRegistry.noop,
                     preferredFailureDomain = Some("zone-a"),
                   )
                   .toOption
                   .get
        bytes <- BoundedByteStream.collectBlock(store.get(block.key))
      yield assertTrue(bytes.bytes == block.bytes, remoteReads.get() == 0)
    },
  )

  private final class CountingFailingStore(reads: AtomicInteger) extends RepairableBlockStore:
    private val failure                                                    = StoreError.Unavailable(StoreOperation.GetBlock, StoreBackend.InMemory, new IOException("replica unavailable"))
    override def putBlocks(plan: BlockWritePlan): BlockSink                =
      ZSink.fail(StoreError.Unavailable(StoreOperation.PutBlock, StoreBackend.InMemory, new IOException("replica unavailable")))
    override def get(key: BinaryKey.Block): ZStream[Any, StoreError, Byte] =
      reads.incrementAndGet()
      ZStream.fail(failure)
    override def exists(key: BinaryKey.Block): IO[StoreError, Boolean]     = ZIO.fail(failure)
    override def healthCheck: IO[StoreError, Unit]                         = ZIO.fail(failure)
    override def repairBlock(block: CanonicalBlock): IO[StoreError, Unit]  = ZIO.fail(failure)

  private object FailingStore extends RepairableBlockStore:
    private val failure                                                    = StoreError.Unavailable(StoreOperation.GetBlock, StoreBackend.InMemory, new IOException("replica unavailable"))
    override def putBlocks(plan: BlockWritePlan): BlockSink                =
      ZSink.fail(StoreError.Unavailable(StoreOperation.PutBlock, StoreBackend.InMemory, new IOException("replica unavailable")))
    override def get(key: BinaryKey.Block): ZStream[Any, StoreError, Byte] = ZStream.fail(failure)
    override def exists(key: BinaryKey.Block): IO[StoreError, Boolean]     = ZIO.fail(failure)
    override def healthCheck: IO[StoreError, Unit]                         = ZIO.fail(failure)
    override def repairBlock(block: CanonicalBlock): IO[StoreError, Unit]  = ZIO.fail(failure)

  private def canonical(value: String): Task[CanonicalBlock] =
    val bytes = Chunk.fromArray(value.getBytes(StandardCharsets.UTF_8))
    for
      hasher <- ZIO.fromEither(Hasher.systemDefault).mapError(new IllegalArgumentException(_))
      _      <- ZIO.attempt(hasher.update(bytes))
      digest <- ZIO.fromEither(hasher.digest).mapError(new IllegalArgumentException(_))
      bits   <- ZIO.fromEither(KeyBits.fromLong(hasher.algo, digest, bytes.length.toLong)).mapError(new IllegalArgumentException(_))
      key    <- ZIO.fromEither(BinaryKey.block(bits)).mapError(new IllegalArgumentException(_))
      block  <- ZIO.fromEither(CanonicalBlock.make(key, bytes, BinaryAttributes.empty)).mapError(new IllegalArgumentException(_))
    yield block
