package graviton.runtime.stores

import graviton.core.bytes.Hasher
import graviton.core.keys.BinaryKey
import graviton.core.model.Block.*
import graviton.runtime.model.*
import graviton.streams.BoundedByteStream
import zio.*
import zio.stream.*

/**
 * Quorum-writing, integrity-checking block store across independent backends.
 * Reads fall through corrupt or unavailable replicas and repair missing copies
 * from a validated source.
 */
final class ReplicatedBlockStore private (
  replicas: Chunk[ReplicatedBlockStore.Replica],
  writeQuorum: Int,
) extends BlockStore:
  import ReplicatedBlockStore.*

  override def putBlocks(plan: BlockWritePlan = BlockWritePlan()): BlockSink =
    ZSink
      .foldLeftZIO(WriteAcc.empty) { (acc, block: CanonicalBlock) =>
        writeOne(block, plan).flatMap(status => acc.append(block, status))
      }
      .mapZIO(_.result)
      .ignoreLeftover

  override def get(key: BinaryKey.Block): ZStream[Any, Throwable, Byte] =
    ZStream.unwrap {
      inspectReplicas(key).flatMap { states =>
        states.collectFirst { case ReplicaState.Valid(_, bytes) => bytes } match
          case None        =>
            ZIO.fail(new NoSuchElementException(s"No valid replica for ${key.bits.render}"))
          case Some(bytes) =>
            val missing = states.collect { case ReplicaState.Unavailable(replica, _) => replica }
            ZIO.foreachDiscard(missing)(replica => repairReplica(replica, key, bytes).forkDaemon).as(ZStream.fromChunk(bytes))
      }
    }

  override def exists(key: BinaryKey.Block): Task[Boolean] =
    inspectReplicas(key).map(_.exists {
      case ReplicaState.Valid(_, _) => true
      case _                        => false
    })

  override def healthCheck: Task[Unit] =
    ZIO.foreachPar(replicas)(replica => replica.store.healthCheck.either).flatMap { checks =>
      val healthy = checks.count(_.isRight)
      if healthy >= writeQuorum then ZIO.unit
      else ZIO.fail(WriteQuorumFailed(writeQuorum, healthy, replicas.length))
    }

  /** Validate every copy and synchronously repair missing replicas. */
  def repair(key: BinaryKey.Block): Task[RepairReport] =
    inspectReplicas(key).flatMap { states =>
      states.collectFirst { case ReplicaState.Valid(_, bytes) => bytes } match
        case None        => ZIO.fail(new NoSuchElementException(s"No valid source replica for ${key.bits.render}"))
        case Some(bytes) =>
          val targets = states.collect { case ReplicaState.Unavailable(replica, _) => replica }
          ZIO.foreach(targets)(replica => repairReplica(replica, key, bytes).either.map(replica.name -> _)).map { repaired =>
            RepairReport(
              validReplicas = states.count(_.isInstanceOf[ReplicaState.Valid]),
              repairedReplicas = repaired.count(_._2.isRight),
              failedReplicas = repaired.collect { case (name, Left(error)) =>
                name -> Option(error.getMessage).getOrElse(error.getClass.getSimpleName)
              }.toMap,
            )
          }
    }

  private def writeOne(block: CanonicalBlock, plan: BlockWritePlan): Task[BlockStoredStatus] =
    ZIO
      .foreachPar(replicas) { replica =>
        ZStream.succeed(block).run(replica.store.putBlocks(plan)).either.map(replica.name -> _)
      }
      .flatMap { results =>
        val successes = results.collect { case (_, Right(result)) => result }
        if successes.length < writeQuorum then ZIO.fail(WriteQuorumFailed(writeQuorum, successes.length, replicas.length))
        else if successes.exists(_.stored.exists(_.status == BlockStoredStatus.Fresh)) then ZIO.succeed(BlockStoredStatus.Fresh)
        else ZIO.succeed(BlockStoredStatus.Duplicate)
      }

  private def inspectReplicas(key: BinaryKey.Block): UIO[Chunk[ReplicaState]] =
    ZIO.foreachPar(replicas) { replica =>
      readValidated(replica, key).fold(
        error => ReplicaState.Unavailable(replica, error),
        bytes => ReplicaState.Valid(replica, bytes),
      )
    }

  private def readValidated(replica: Replica, key: BinaryKey.Block): Task[Chunk[Byte]] =
    BoundedByteStream.collectBlock(replica.store.get(key)).flatMap { block =>
      validate(key, block.bytes).as(block.bytes)
    }

  private def validate(key: BinaryKey.Block, bytes: Chunk[Byte]): Task[Unit] =
    for
      _      <-
        ZIO.fail(new IllegalStateException(s"Replica length mismatch for ${key.bits.render}")).unless(bytes.length.toLong == key.bits.size)
      hasher <- ZIO.fromEither(Hasher.hasher(key.bits.algo)).mapError(new IllegalArgumentException(_))
      _      <- ZIO.attempt(hasher.update(bytes.toArray))
      digest <- ZIO.fromEither(hasher.digest).mapError(new IllegalArgumentException(_))
      _      <- ZIO.fail(new IllegalStateException(s"Replica digest mismatch for ${key.bits.render}")).unless(digest == key.bits.digest)
    yield ()

  private def repairReplica(replica: Replica, key: BinaryKey.Block, bytes: Chunk[Byte]): Task[Unit] =
    for
      block <- ZIO.fromEither(CanonicalBlock.make(key, bytes)).mapError(new IllegalArgumentException(_))
      _     <- ZStream.succeed(block).run(replica.store.putBlocks()).unit
    yield ()

object ReplicatedBlockStore:
  final case class Replica(name: String, store: BlockStore):
    require(name.trim.nonEmpty, "replica name must be non-empty")

  final case class WriteQuorumFailed(required: Int, succeeded: Int, total: Int)
      extends RuntimeException(s"Block write quorum failed: required=$required succeeded=$succeeded total=$total")

  final case class RepairReport(
    validReplicas: Int,
    repairedReplicas: Int,
    failedReplicas: Map[String, String],
  )

  private enum ReplicaState:
    case Valid(replica: Replica, bytes: Chunk[Byte])
    case Unavailable(replica: Replica, error: Throwable)

  def make(replicas: Chunk[Replica], writeQuorum: Int): Either[String, ReplicatedBlockStore] =
    Either.cond(
      replicas.nonEmpty && writeQuorum >= 1 && writeQuorum <= replicas.length && replicas.map(_.name).distinct.length == replicas.length,
      new ReplicatedBlockStore(replicas, writeQuorum),
      "replicas must be non-empty with unique names and writeQuorum within replica count",
    )

  private final case class WriteAcc(
    entries: ChunkBuilder[BlockManifestEntry],
    stored: ChunkBuilder[StoredBlock],
    offset: Long,
    index: Long,
  ):
    def append(block: CanonicalBlock, status: BlockStoredStatus): Task[WriteAcc] =
      ZIO
        .fromEither(BlockManifestEntry.make(index, offset, block.key, block.size.value))
        .mapError(new IllegalArgumentException(_))
        .map { entry =>
          entries += entry
          stored += StoredBlock(block.key, block.size, status)
          copy(offset = offset + block.size.value.toLong, index = index + 1L)
        }

    def result: Task[BlockBatchResult] =
      ZIO.fromEither(BlockManifest.build(entries.result())).mapError(new IllegalArgumentException(_)).map { manifest =>
        BlockBatchResult(manifest, stored.result(), Chunk.empty, Chunk.empty)
      }

  private object WriteAcc:
    def empty: WriteAcc = WriteAcc(ChunkBuilder.make(), ChunkBuilder.make(), 0L, 0L)
