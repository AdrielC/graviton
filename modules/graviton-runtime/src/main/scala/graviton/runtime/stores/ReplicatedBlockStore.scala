package graviton.runtime.stores

import graviton.core.attributes.BinaryAttributes
import graviton.core.bytes.Hasher
import graviton.core.keys.BinaryKey
import graviton.core.model.Block
import graviton.core.model.Block.*
import graviton.runtime.metrics.{MetricKeys, MetricsRegistry}
import graviton.runtime.model.*
import graviton.streams.BoundedByteStream
import zio.*
import zio.stream.*

/**
 * Failure-domain-aware block replication with deterministic rendezvous
 * placement, quorum writes, validating fallback reads, and bounded repair.
 *
 * At most two compile-time bounded blocks are retained while comparing a
 * source and candidate. Arbitrary blob streams are never materialized.
 */
final class ReplicatedBlockStore private (
  replicas: Chunk[ReplicatedBlockStore.Replica],
  desiredReplicas: Int,
  writeQuorum: Int,
  placement: ReplicaPlacement,
  metrics: MetricsRegistry,
  preferredFailureDomain: Option[String],
) extends ConvergentBlockStore
    with BlockTransferFootprint:
  import ReplicatedBlockStore.*

  override val transferBackend: StoreBackend = StoreBackend.Runtime

  override def blockWriteFootprint(maximumBlockBytes: Int): Either[TransferFootprint.Error, TransferFootprint] =
    val targetTotals = replicas.map(replica => BlockTransferFootprint.writeOf(replica.store, maximumBlockBytes).map(_.totalBytes))
    targetTotals
      .foldLeft[Either[TransferFootprint.Error, Chunk[Long]]](Right(Chunk.empty)) { (acc, next) =>
        for
          values <- acc
          value  <- next
        yield values :+ value
      }
      .flatMap { totals =>
        totals
          .sorted(using Ordering.Long.reverse)
          .take(desiredReplicas)
          .foldLeft[Either[TransferFootprint.Error, Long]](Right(0L)) { (sum, value) =>
            sum.flatMap(TransferFootprint.add(_, value))
          }
          .flatMap(TransferFootprint.single(TransferComponent.applyUnsafe("replica-write-fanout"), _))
      }

  override def putBlock(
    block: CanonicalBlock,
    plan: BlockWritePlan = BlockWritePlan(),
  ): IO[StoreError, StoredBlock] =
    writeOne(block, plan)
      .map(status => StoredBlock(block.key, block.size, status))
      .mapError(storageError(StoreOperation.PutBlock))

  override def putBlocks(plan: BlockWritePlan = BlockWritePlan()): BlockSink =
    ZSink
      .foldLeftZIO(WriteAcc.empty) { (acc, block: CanonicalBlock) =>
        writeOne(block, plan).flatMap(status => acc.append(block, status))
      }
      .mapZIO(_.result)
      .mapError(storageError(StoreOperation.PutBlock))
      .ignoreLeftover

  override def get(key: BinaryKey.Block): ZStream[Any, StoreError, Byte] =
    ZStream
      .unwrap {
        for
          selected <- select(key)
          source   <- findSource(key, sourceCandidates(selected))
          locality  =
            preferredFailureDomain.fold("unconfigured")(domain => if source.replica.failureDomain == domain then "local" else "remote")
          _        <- metrics.counter(MetricKeys.ReplicaReadsTotal, Map("replica" -> source.replica.name, "locality" -> locality))
          targets   = selected.iterator.map(_.name).toSet
          _        <- ZIO.foreachDiscard(source.failedBefore.filter { case (replica, _) => targets.contains(replica.name) }) { case (replica, _) =>
                        replaceReplica(replica, source.block, "read_repair").ignore
                      }
        yield ZStream.fromChunk(source.block.bytes)
      }
      .mapError(storageError(StoreOperation.GetBlock))

  override def converge(key: BinaryKey.Block): IO[StoreError, RepairConvergence] =
    repairInternal(key)
      .map(report => RepairConvergence(report.validReplicas, report.repairedReplicas, report.failedReplicas))
      .mapError(storageError(StoreOperation.Repair))

  override def exists(key: BinaryKey.Block): IO[StoreError, Boolean] =
    select(key)
      .flatMap(selected => findSource(key, sourceCandidates(selected)))
      .as(true)
      .catchSome { case _: NoValidReplica => ZIO.succeed(false) }
      .mapError(storageError(StoreOperation.ExistsBlock))

  override def healthCheck: IO[StoreError, Unit] =
    ZIO
      .foreachPar(replicas)(replica => replica.store.healthCheck.either)
      .flatMap { checks =>
        val healthy = checks.count(_.isRight)
        metrics.gauge(MetricKeys.ReplicaHealthyTargets, healthy.toDouble, Map.empty) *>
          ZIO
            .fail(WriteQuorumFailed(writeQuorum, healthy, replicas.length))
            .when(healthy < writeQuorum)
            .unit
      }
      .mapError(storageError(StoreOperation.HealthCheck))

  /** Validate the selected copies and atomically replace every bad replica. */
  def repair(key: BinaryKey.Block): IO[StoreError, RepairReport] =
    repairInternal(key).mapError(storageError(StoreOperation.Repair))

  private def storageError(operation: StoreOperation)(error: Throwable): StoreError =
    error match
      case WriteQuorumFailed(required, succeeded, total) =>
        StoreError.QuorumUnavailable(operation, required, succeeded, total)
      case InvalidPlacement(expected, actual)            =>
        StoreError.InvalidInput(operation, s"replica placement returned $actual targets, expected $expected")
      case NoValidReplica(key, failures)                 =>
        StoreError.NoHealthyReplica(operation, key, failures)
      case other                                         =>
        StoreError.fromThrowable(operation, retryUnknown = true)(other)

  private def repairInternal(key: BinaryKey.Block): Task[RepairReport] =
    for
      selected <- select(key)
      source   <- findSource(key, sourceCandidates(selected))
      report   <- ZIO.foldLeft(selected)(RepairAcc.empty) { (acc, replica) =>
                    if replica.name == source.replica.name then ZIO.succeed(acc.valid)
                    else
                      source.failedBefore.find(_._1.name == replica.name) match
                        case Some((_, initialError)) => repairKnownBad(acc, replica, source.block, initialError)
                        case None                    =>
                          readValidated(replica, key).either.flatMap {
                            case Right(_)    => ZIO.succeed(acc.valid)
                            case Left(error) => repairKnownBad(acc, replica, source.block, error)
                          }
                  }
      result    = RepairReport(report.validReplicas, report.repairedReplicas, report.failedReplicas)
      _        <- metrics.counterBy(MetricKeys.ReplicaRepairsTotal, result.repairedReplicas.toLong, Map("outcome" -> "repaired"))
      _        <- metrics.counterBy(MetricKeys.ReplicaRepairsTotal, result.failedReplicas.size.toLong, Map("outcome" -> "failed"))
    yield result

  /**
   * Selected targets are always tried first. Remaining configured targets are
   * recovery sources when a topology expansion changes rendezvous placement;
   * scheduled repair then migrates the block into its new selected set.
   */
  private def sourceCandidates(selected: Chunk[Replica]): Chunk[Replica] =
    val selectedNames = selected.iterator.map(_.name).toSet
    preferLocal(selected) ++ preferLocal(replicas.filterNot(replica => selectedNames.contains(replica.name)))

  private def preferLocal(candidates: Chunk[Replica]): Chunk[Replica] =
    preferredFailureDomain match
      case None         => candidates
      case Some(domain) => candidates.sortBy(replica => if replica.failureDomain == domain then 0 else 1)

  private def repairKnownBad(
    acc: RepairAcc,
    replica: Replica,
    source: CanonicalBlock,
    initialError: Throwable,
  ): UIO[RepairAcc] =
    replaceReplica(replica, source, "scheduled_repair").either.map {
      case Right(_)    => acc.repaired
      case Left(error) =>
        val detail = Option(error.getMessage)
          .orElse(Option(initialError.getMessage))
          .getOrElse(error.getClass.getSimpleName)
        acc.failed(replica.name, detail)
    }

  private def writeOne(block: CanonicalBlock, plan: BlockWritePlan): Task[BlockStoredStatus] =
    for
      selected <- select(block.key)
      results  <- ZIO.foreachPar(selected) { replica =>
                    replica.store.putBlock(block, plan).either.flatMap { result =>
                      metrics
                        .counter(
                          MetricKeys.ReplicaWritesTotal,
                          Map("replica" -> replica.name, "outcome" -> result.fold(_ => "failed", _ => "succeeded")),
                        )
                        .as(replica.name -> result)
                    }
                  }
      successes = results.collect { case (_, Right(result)) => result }
      status   <-
        if successes.length < writeQuorum then ZIO.fail(WriteQuorumFailed(writeQuorum, successes.length, selected.length))
        else if successes.exists(_.status == BlockStoredStatus.Fresh) then ZIO.succeed(BlockStoredStatus.Fresh)
        else ZIO.succeed(BlockStoredStatus.Duplicate)
    yield status

  private def select(key: BinaryKey.Block): Task[Chunk[Replica]] =
    placement.select(key, replicas, desiredReplicas).flatMap { selected =>
      metrics.counter(MetricKeys.ReplicaPlacementsTotal, Map("replicas" -> selected.length.toString)) *>
        ZIO
          .fail(InvalidPlacement(desiredReplicas, selected.length))
          .unless(selected.length == desiredReplicas)
          .as(selected)
    }

  private def findSource(key: BinaryKey.Block, candidates: Chunk[Replica]): Task[Source] =
    def loop(remaining: List[Replica], failed: Chunk[(Replica, Throwable)]): Task[Source] =
      remaining match
        case Nil          =>
          ZIO.fail(
            NoValidReplica(
              key,
              failed.iterator.map { case (replica, error) =>
                replica.name -> Option(error.getMessage).getOrElse(error.getClass.getSimpleName)
              }.toMap,
            )
          )
        case head :: tail =>
          readValidated(head, key).foldZIO(
            error => loop(tail, failed :+ (head -> error)),
            block => ZIO.succeed(Source(head, block, failed)),
          )

    loop(candidates.toList, Chunk.empty)

  private def readValidated(replica: Replica, key: BinaryKey.Block): Task[CanonicalBlock] =
    BoundedByteStream.collectBlock(replica.store.get(key)).flatMap { block =>
      validate(key, block) *>
        ZIO
          .fromEither(CanonicalBlock.make(key, block.bytes, BinaryAttributes.empty))
          .mapError(new IllegalStateException(_))
    }

  private def validate(key: BinaryKey.Block, bytes: Block): Task[Unit] =
    for
      _      <- ZIO
                  .fail(new IllegalStateException(s"Replica length mismatch for ${key.bits.render}"))
                  .unless(bytes.length.toLong == key.bits.size)
      hasher <- ZIO.fromEither(Hasher.hasher(key.bits.algo)).mapError(new IllegalArgumentException(_))
      _      <- ZIO.attempt(hasher.update(bytes.bytes))
      digest <- ZIO.fromEither(hasher.digest).mapError(new IllegalArgumentException(_))
      _      <- ZIO
                  .fail(new IllegalStateException(s"Replica digest mismatch for ${key.bits.render}"))
                  .unless(digest == key.bits.digest)
    yield ()

  private def replaceReplica(replica: Replica, block: CanonicalBlock, trigger: String): Task[Unit] =
    val replace =
      replica.store match
        case repairable: RepairableBlockStore => repairable.repairBlock(block)
        case compatible                       => compatible.putBlock(block).unit

    replace
      .tapBoth(
        _ =>
          metrics
            .counter(MetricKeys.ReplicaRepairAttemptsTotal, Map("replica" -> replica.name, "trigger" -> trigger, "outcome" -> "failed")),
        _ =>
          metrics
            .counter(MetricKeys.ReplicaRepairAttemptsTotal, Map("replica" -> replica.name, "trigger" -> trigger, "outcome" -> "succeeded")),
      )

object ReplicatedBlockStore:
  final case class Replica(name: String, store: BlockStore, failureDomain: String):
    require(name.trim.nonEmpty, "replica name must be non-empty")
    require(failureDomain.trim.nonEmpty, "replica failureDomain must be non-empty")

    /** Binary-compatible constructor for the public replica descriptor shipped in 0.6.1. */
    def this(name: String, store: BlockStore) = this(name, store, name)

    /** Binary-compatible copy method for the public replica descriptor shipped in 0.6.1. */
    def copy(name: String, store: BlockStore): Replica = new Replica(name, store, failureDomain)

  object Replica:
    /** Binary-compatible factory for the public replica descriptor shipped in 0.6.1. */
    def apply(name: String, store: BlockStore): Replica = new Replica(name, store)

    def apply(name: String, failureDomain: String, store: RepairableBlockStore): Replica =
      new Replica(name, store, failureDomain)

  private final case class WriteQuorumFailed(required: Int, succeeded: Int, total: Int)
      extends RuntimeException(s"Block write quorum failed: required=$required succeeded=$succeeded total=$total")

  private final case class InvalidPlacement(expected: Int, actual: Int)
      extends RuntimeException(s"Replica placement returned $actual targets, expected $expected")

  private final case class NoValidReplica(key: BinaryKey.Block, failures: Map[String, String])
      extends RuntimeException(s"No valid replica for ${key.bits.render}; checked ${failures.keys.toList.sorted.mkString(",")}")

  final case class RepairReport(
    validReplicas: Int,
    repairedReplicas: Int,
    failedReplicas: Map[String, String],
  )

  /** Retained for binary compatibility with 0.6.1. New repair internals validate one bounded candidate at a time. */
  private enum ReplicaState:
    case Valid(replica: Replica, bytes: Chunk[Byte])
    case Unavailable(replica: Replica, error: Throwable)

  def make(replicas: Chunk[Replica], writeQuorum: Int): Either[String, ReplicatedBlockStore] =
    make(replicas, replicas.length, writeQuorum, ReplicaPlacement.rendezvous, MetricsRegistry.noop, None)

  def make(
    replicas: Chunk[Replica],
    desiredReplicas: Int,
    writeQuorum: Int,
    placement: ReplicaPlacement,
    metrics: MetricsRegistry = MetricsRegistry.noop,
  ): Either[String, ReplicatedBlockStore] =
    make(replicas, desiredReplicas, writeQuorum, placement, metrics, None)

  def make(
    replicas: Chunk[Replica],
    desiredReplicas: Int,
    writeQuorum: Int,
    placement: ReplicaPlacement,
    metrics: MetricsRegistry,
    preferredFailureDomain: Option[String],
  ): Either[String, ReplicatedBlockStore] =
    Either.cond(
      replicas.nonEmpty &&
        desiredReplicas >= 1 &&
        desiredReplicas <= replicas.length &&
        writeQuorum >= 1 &&
        writeQuorum <= desiredReplicas &&
        replicas.map(_.name).distinct.length == replicas.length,
      new ReplicatedBlockStore(replicas, desiredReplicas, writeQuorum, placement, metrics, preferredFailureDomain),
      "replicas must be non-empty with unique names, desiredReplicas within target count, and writeQuorum within desiredReplicas",
    )

  private final case class Source(
    replica: Replica,
    block: CanonicalBlock,
    failedBefore: Chunk[(Replica, Throwable)],
  )

  private final case class RepairAcc(
    validReplicas: Int,
    repairedReplicas: Int,
    failedReplicas: Map[String, String],
  ):
    def valid: RepairAcc                                   = copy(validReplicas = validReplicas + 1)
    def repaired: RepairAcc                                = copy(repairedReplicas = repairedReplicas + 1)
    def failed(replica: String, detail: String): RepairAcc = copy(failedReplicas = failedReplicas.updated(replica, detail))

  private object RepairAcc:
    val empty: RepairAcc = RepairAcc(validReplicas = 0, repairedReplicas = 0, failedReplicas = Map.empty)

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
