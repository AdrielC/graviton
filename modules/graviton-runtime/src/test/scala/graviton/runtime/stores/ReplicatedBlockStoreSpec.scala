package graviton.runtime.stores

import graviton.core.attributes.BinaryAttributes
import graviton.core.bytes.Hasher
import graviton.core.keys.{BinaryKey, KeyBits}
import graviton.runtime.model.*
import zio.*
import zio.stream.*
import zio.test.*
import zio.test.Assertion.*

import java.io.IOException
import java.nio.charset.StandardCharsets

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
      yield assert(exit)(fails(isSubtype[ReplicatedBlockStore.WriteQuorumFailed](anything)))
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
  )

  private object FailingStore extends BlockStore:
    override def putBlocks(plan: BlockWritePlan): BlockSink               = ZSink.fail(new IOException("replica unavailable"))
    override def get(key: BinaryKey.Block): ZStream[Any, Throwable, Byte] = ZStream.fail(new IOException("replica unavailable"))
    override def exists(key: BinaryKey.Block): Task[Boolean]              = ZIO.fail(new IOException("replica unavailable"))
    override def healthCheck: Task[Unit]                                  = ZIO.fail(new IOException("replica unavailable"))

  private def canonical(value: String): Task[CanonicalBlock] =
    val bytes = Chunk.fromArray(value.getBytes(StandardCharsets.UTF_8))
    for
      hasher <- ZIO.fromEither(Hasher.systemDefault).mapError(new IllegalArgumentException(_))
      _      <- ZIO.attempt(hasher.update(bytes.toArray))
      digest <- ZIO.fromEither(hasher.digest).mapError(new IllegalArgumentException(_))
      bits   <- ZIO.fromEither(KeyBits.create(hasher.algo, digest, bytes.length.toLong)).mapError(new IllegalArgumentException(_))
      key    <- ZIO.fromEither(BinaryKey.block(bits)).mapError(new IllegalArgumentException(_))
      block  <- ZIO.fromEither(CanonicalBlock.make(key, bytes, BinaryAttributes.empty)).mapError(new IllegalArgumentException(_))
    yield block
