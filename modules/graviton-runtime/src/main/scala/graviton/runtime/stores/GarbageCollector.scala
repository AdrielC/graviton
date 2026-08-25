package graviton.runtime.stores

import graviton.core.keys.BinaryKey
import zio.*

/** Conservative mark/sweep collector with an explicit quarantine phase. */
final class GarbageCollector(
  manifests: BlobManifestRepo,
  blocks: BlockMaintenance,
):
  import GarbageCollector.*

  def collect(minimumAge: Duration, dryRun: Boolean): Task[Report] =
    for
      now       <- Clock.instant
      marked    <- referencedKeys
      inventory <- blocks.inventory.runCollect
      candidates =
        inventory.filter(entry => !marked.contains(entry.key) && !entry.lastModified.isAfter(now.minusMillis(minimumAge.toMillis)))
      swept     <-
        if dryRun then ZIO.succeed(Chunk.empty)
        else
          // Re-mark immediately before mutation. minimumAge protects blocks
          // uploaded by a concurrent ingest whose manifest is not committed yet.
          referencedKeys.flatMap { current =>
            ZIO.foreach(candidates.filterNot(entry => current.contains(entry.key)))(blocks.quarantine)
          }
    yield Report(
      dryRun = dryRun,
      scannedBlocks = inventory.length,
      referencedBlocks = marked.size,
      candidateBlocks = candidates.length,
      candidateBytes = candidates.foldLeft(0L)(_ + _.size),
      quarantined = swept,
    )

  def restore(quarantined: Chunk[QuarantinedBlock]): Task[Unit] =
    ZIO.foreachDiscard(quarantined)(blocks.restore)

  def purge(quarantined: Chunk[QuarantinedBlock], minimumQuarantineAge: Duration): Task[Int] =
    Clock.instant.flatMap { now =>
      val eligible = quarantined.filter(block => !block.quarantinedAt.isAfter(now.minusMillis(minimumQuarantineAge.toMillis)))
      ZIO.foreachDiscard(eligible)(blocks.purge).as(eligible.length)
    }

  private def referencedKeys: Task[Set[BinaryKey.Block]] =
    manifests.list.map { entries =>
      entries.iterator.flatMap { case (_, stored) =>
        stored.manifest.entries.iterator.collect {
          case entry if entry.key.isInstanceOf[BinaryKey.Block] =>
            entry.key.asInstanceOf[BinaryKey.Block]
        }
      }.toSet
    }

object GarbageCollector:
  final case class Report(
    dryRun: Boolean,
    scannedBlocks: Int,
    referencedBlocks: Int,
    candidateBlocks: Int,
    candidateBytes: Long,
    quarantined: Chunk[QuarantinedBlock],
  )
