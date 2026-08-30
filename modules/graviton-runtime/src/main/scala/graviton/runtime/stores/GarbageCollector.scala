package graviton.runtime.stores

import graviton.core.keys.BinaryKey
import graviton.runtime.config.GarbageCollectionConfig
import zio.*
import zio.stream.ZStream

/**
 * Conservative, exact mark/quarantine collector.
 *
 * Repository-scale sweep never collects the manifest graph, block inventory,
 * or quarantine receipts in heap. It spills bounded key records to a temporary
 * workspace, partitions them by digest, and performs an exact join. Before a
 * mutation it re-marks the persisted candidate file, so a manifest committed
 * between preview and quarantine protects its blocks. Production wiring also
 * holds an exclusive repository maintenance lease across the full run, while
 * minimum age and the second mark remain defense-in-depth controls.
 */
private[stores] abstract class GarbageCollectorBase(
  protected val manifestReferences: ManifestReferenceSource,
  blocks: BlockMaintenance,
  config: GarbageCollectionConfig,
  coordinator: MaintenanceCoordinator,
) extends GarbageCollection:
  import GarbageCollector.*

  /**
   * Run repository-scale collection with a streaming receipt sink.
   *
   * The callback is invoked only after a block reaches quarantine. If it
   * fails, the collector restores that block before failing the run, preserving
   * the caller's ability to maintain a durable receipt without silently
   * stranding an unrecorded block.
   */
  override def sweep(
    minimumAge: Duration,
    dryRun: Boolean,
  )(
    onQuarantined: QuarantinedBlock => IO[StoreError, Unit] = _ => ZIO.unit
  ): IO[StoreError, SweepReport] =
    run(minimumAge, dryRun, None)(onQuarantined)
      .map(_.report)
      .mapError(StoreError.fromThrowable(StoreOperation.GarbageCollect))

  /**
   * Small-batch compatibility API.
   *
   * This keeps the v0.5 receipt shape for callers that deliberately need a
   * `Chunk[QuarantinedBlock]`. It rejects oversized repository or receipt
   * collections before any mutation. Use [[sweep]] for production inventory.
   */
  def collect(minimumAge: Duration, dryRun: Boolean): IO[StoreError, Report] =
    (for
      receipts   <- Ref.make(ChunkBuilder.make[QuarantinedBlock]())
      outcome    <- run(
                      minimumAge,
                      dryRun,
                      Some(CompatibilityLimits(config.maxCompatibilityReferences, config.maxCompatibilityReceipts)),
                    ) { block =>
                      receipts.update(_ += block).unit
                    }
      values     <- receipts.get.map(_.result())
      scanned    <- ZIO.attempt(java.lang.Math.toIntExact(outcome.report.scannedBlocks))
      candidates <- ZIO.attempt(java.lang.Math.toIntExact(outcome.report.candidateBlocks))
      _          <- ZIO
                      .fail(
                        new IllegalStateException(
                          s"Compatibility receipt mismatch: reported ${outcome.report.quarantinedBlocks}, retained ${values.length}"
                        )
                      )
                      .unless(dryRun || outcome.report.quarantinedBlocks == values.length.toLong)
    yield Report(
      dryRun = outcome.report.dryRun,
      scannedBlocks = scanned,
      referencedBlocks = outcome.compatibilityDistinctReferences.getOrElse(0),
      candidateBlocks = candidates,
      candidateBytes = outcome.report.candidateBytes,
      quarantined = values,
    )).mapError(StoreError.fromThrowable(StoreOperation.GarbageCollect))

  def restore(quarantined: Chunk[QuarantinedBlock]): IO[StoreError, Unit] =
    restore(ZStream.fromChunk(quarantined)).unit

  /** Restore a durable quarantine receipt without first collecting it. */
  override def restore(quarantined: ZStream[Any, StoreError, QuarantinedBlock]): IO[StoreError, Long] =
    coordinator
      .withMaintenance {
        quarantined.runFoldZIO(0L) { (count, block) =>
          blocks.restore(block).as(java.lang.Math.addExact(count, 1L))
        }
      }
      .mapError(StoreError.fromThrowable(StoreOperation.Restore))

  def purge(quarantined: Chunk[QuarantinedBlock], minimumQuarantineAge: Duration): IO[StoreError, Int] =
    purge(ZStream.fromChunk(quarantined), minimumQuarantineAge)
      .flatMap(count => ZIO.attempt(java.lang.Math.toIntExact(count)).mapError(StoreError.fromThrowable(StoreOperation.Purge)))

  /** Purge an already-reviewed quarantine receipt as a stream. */
  override def purge(
    quarantined: ZStream[Any, StoreError, QuarantinedBlock],
    minimumQuarantineAge: Duration,
  ): IO[StoreError, Long] =
    coordinator
      .withMaintenance {
        ZIO
          .fail(new IllegalArgumentException("minimumQuarantineAge must be non-negative"))
          .when(minimumQuarantineAge < Duration.Zero) *>
          Clock.instant.flatMap { now =>
            val oldestEligible = now.minusMillis(minimumQuarantineAge.toMillis)
            quarantined.runFoldZIO(0L) { (count, block) =>
              if block.quarantinedAt.isAfter(oldestEligible) then ZIO.succeed(count)
              else blocks.purge(block).as(java.lang.Math.addExact(count, 1L))
            }
          }
      }
      .mapError(StoreError.fromThrowable(StoreOperation.Purge))

  private def run(
    minimumAge: Duration,
    dryRun: Boolean,
    compatibility: Option[CompatibilityLimits],
  )(
    onQuarantined: QuarantinedBlock => Task[Unit]
  ): Task[RunResult] =
    coordinator.withMaintenance(runUnlocked(minimumAge, dryRun, compatibility)(onQuarantined))

  private def runUnlocked(
    minimumAge: Duration,
    dryRun: Boolean,
    compatibility: Option[CompatibilityLimits],
  )(
    onQuarantined: QuarantinedBlock => Task[Unit]
  ): Task[RunResult] =
    for
      _      <- ZIO
                  .fail(new IllegalArgumentException("minimumAge must be non-negative"))
                  .when(minimumAge < Duration.Zero)
      _      <- ZIO
                  .fromEither(config.validate)
                  .mapError(new IllegalArgumentException(_))
      report <- GarbageCollectionSpool.scoped(
                  GarbageCollectionSpool.Config(config.maxReferencesPerPartition, config.maximumPartitionDepth),
                  config.workspaceDirectory,
                ) { workspace =>
                  for
                    now                   <- Clock.instant
                    oldestEligible         = now.minusMillis(minimumAge.toMillis)
                    initialReferences     <- workspace.writeReferences("mark-initial", referencedBlocks)
                    inventory             <- workspace.writeInventory("inventory", blocks.inventory, oldestEligible)
                    candidates            <- workspace.captureUnreferenced("candidates", initialReferences, inventory)
                    compatibilityDistinct <- compatibility match
                                               case None         => ZIO.none
                                               case Some(limits) =>
                                                 for
                                                   _    <- requireCompatibilityBounds(initialReferences, candidates, inventory, limits)
                                                   keys <- workspace.distinctReferencesWithin(limits.maxReferences, initialReferences)
                                                 yield Some(keys.size)
                    initial                = SweepReport(
                                               dryRun = dryRun,
                                               scannedBlocks = inventory.scannedBlocks,
                                               eligibleBlocks = inventory.eligibleBlocks,
                                               referencedBlockRefs = initialReferences.referenceEntries,
                                               candidateBlocks = candidates.counts.blocks,
                                               candidateBytes = candidates.counts.bytes,
                                               quarantinedBlocks = 0L,
                                               quarantinedBytes = 0L,
                                             )
                    finished              <-
                      if dryRun then ZIO.succeed(initial)
                      else
                        for
                          currentReferences <- workspace.writeReferences("mark-before-quarantine", referencedBlocks)
                          quarantined       <- workspace.visitUnreferenced(currentReferences, candidates.path) { entry =>
                                                 quarantineWithReceipt(entry, onQuarantined)
                                               }
                        yield initial.copy(
                          dryRun = false,
                          quarantinedBlocks = quarantined.blocks,
                          quarantinedBytes = quarantined.bytes,
                        )
                  yield RunResult(finished, compatibilityDistinct)
                }
    yield report

  private def referencedBlocks: ZStream[Any, StoreError, BinaryKey.Block] =
    manifestReferences.referencedBlocks

  private def requireCompatibilityBounds(
    references: GarbageCollectionSpool.ReferenceCapture,
    candidates: GarbageCollectionSpool.CandidateCapture,
    inventory: GarbageCollectionSpool.InventoryCapture,
    limits: CompatibilityLimits,
  ): Task[Unit] =
    ZIO
      .fail(
        new IllegalArgumentException(
          s"Compatibility collection has ${references.referenceEntries} reference entries, above ${limits.maxReferences}; use GarbageCollector.sweep"
        )
      )
      .when(references.referenceEntries > limits.maxReferences.toLong) *>
      ZIO
        .fail(
          new IllegalArgumentException(
            s"Compatibility collection has ${candidates.counts.blocks} candidate receipts, above ${limits.maxReceipts}; use GarbageCollector.sweep"
          )
        )
        .when(candidates.counts.blocks > limits.maxReceipts.toLong) *>
      ZIO
        .fail(
          new IllegalArgumentException(
            s"Compatibility collection scanned ${inventory.scannedBlocks} blocks, above Int.MaxValue; use GarbageCollector.sweep"
          )
        )
        .when(inventory.scannedBlocks > Int.MaxValue.toLong)
        .unit

  private def quarantineWithReceipt(
    entry: BlockInventoryEntry,
    onQuarantined: QuarantinedBlock => Task[Unit],
  ): Task[Unit] =
    blocks.quarantine(entry).flatMap { quarantined =>
      onQuarantined(quarantined).catchAll { receiptFailure =>
        blocks
          .restore(quarantined)
          .foldZIO(
            restoreFailure =>
              ZIO
                .fail(
                  new IllegalStateException(
                    s"Quarantine receipt failed for ${entry.key.bits.render}, and the compensating restore also failed",
                    restoreFailure,
                  )
                )
                .tapError(error => ZIO.succeed(error.addSuppressed(receiptFailure))),
            _ => ZIO.fail(receiptFailure),
          )
      }
    }

/**
 * Collector for one manifest repository and its physical block namespace.
 *
 * The constructor and defaults are retained from 0.7.0 for source and binary
 * compatibility. Shared physical domains must use [[GarbageCollector.forStorageDomain]].
 */
final class GarbageCollector(
  manifests: BlobManifestRepo,
  blocks: BlockMaintenance,
  config: GarbageCollectionConfig = GarbageCollectionConfig.Default,
  coordinator: MaintenanceCoordinator = MaintenanceCoordinator.uncoordinated,
) extends GarbageCollectorBase(ManifestReferenceSource.repository(manifests), blocks, config, coordinator):

  /** Retained for callers compiled against the 0.7.0 convenience constructor. */
  def this(manifests: BlobManifestRepo, blocks: BlockMaintenance) =
    this(manifests, blocks, GarbageCollectionConfig.Default, MaintenanceCoordinator.uncoordinated)

  /** Retained for callers compiled against the 0.7.0 configured constructor. */
  def this(
    manifests: BlobManifestRepo,
    blocks: BlockMaintenance,
    config: GarbageCollectionConfig,
  ) = this(manifests, blocks, config, MaintenanceCoordinator.uncoordinated)

object GarbageCollector:
  /**
   * Construct GC for one physical block domain shared by multiple manifest
   * repositories. Every repository capable of referencing the domain must be
   * present. The shared coordinator must cover all writers and this collector.
   */
  def forStorageDomain(
    manifests: NonEmptyChunk[BlobManifestRepo],
    blocks: BlockMaintenance,
    config: GarbageCollectionConfig,
    coordinator: MaintenanceCoordinator,
  ): GarbageCollection =
    new GarbageCollectorBase(ManifestReferenceSource.repositories(manifests), blocks, config, coordinator) {}

  def forReferenceSource(
    manifestReferences: ManifestReferenceSource,
    blocks: BlockMaintenance,
    config: GarbageCollectionConfig,
    coordinator: MaintenanceCoordinator,
  ): GarbageCollection =
    new GarbageCollectorBase(manifestReferences, blocks, config, coordinator) {}

  final case class SweepReport(
    dryRun: Boolean,
    scannedBlocks: Long,
    eligibleBlocks: Long,
    referencedBlockRefs: Long,
    candidateBlocks: Long,
    candidateBytes: Long,
    quarantinedBlocks: Long,
    quarantinedBytes: Long,
  )

  /** Existing bounded receipt report retained for source and binary compatibility. */
  final case class Report(
    dryRun: Boolean,
    scannedBlocks: Int,
    referencedBlocks: Int,
    candidateBlocks: Int,
    candidateBytes: Long,
    quarantined: Chunk[QuarantinedBlock],
  )

  private[stores] final case class CompatibilityLimits(
    maxReferences: Int,
    maxReceipts: Int,
  )

  private[stores] final case class RunResult(
    report: SweepReport,
    compatibilityDistinctReferences: Option[Int],
  )
