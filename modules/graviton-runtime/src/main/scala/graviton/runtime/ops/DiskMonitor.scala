package graviton.runtime.ops

import graviton.runtime.metrics.MetricsRegistry
import zio.*

import java.nio.file.{Files, Path}

final case class DiskSnapshot(
  totalBytes: Long,
  usableBytes: Long,
  unallocatedBytes: Long,
)

final case class DiskThresholds(
  minUsableBytes: Long,
  minUsablePercent: Double,
)

final class DiskMonitor private (
  path: Path,
  thresholds: DiskThresholds,
  registry: MetricsRegistry,
):

  def sample: Task[DiskSnapshot] =
    ZIO.attemptBlocking {
      val store = Files.getFileStore(path)
      DiskSnapshot(
        totalBytes = store.getTotalSpace,
        usableBytes = store.getUsableSpace,
        unallocatedBytes = store.getUnallocatedSpace,
      )
    }

  def isLowDisk(snapshot: DiskSnapshot): Boolean =
    val pct = if snapshot.totalBytes <= 0 then 0.0 else snapshot.usableBytes.toDouble / snapshot.totalBytes.toDouble
    snapshot.usableBytes < thresholds.minUsableBytes || pct < thresholds.minUsablePercent

  /**
   * Periodically sample disk and publish gauges.
   *
   * This is intentionally lightweight: storage backends can consult these gauges or
   * couple this with a CircuitBreaker/Bulkhead for hard fail-fast behavior.
   */
  def run(interval: Duration): URIO[Scope, Unit] =
    (for
      snap <- sample.orElseSucceed(DiskSnapshot(0L, 0L, 0L))
      pct   = if snap.totalBytes <= 0 then 0.0 else snap.usableBytes.toDouble / snap.totalBytes.toDouble
      tags  = Map("path" -> path.toString)
      _    <- registry.gauge("graviton_disk_total_bytes", snap.totalBytes.toDouble, tags)
      _    <- registry.gauge("graviton_disk_usable_bytes", snap.usableBytes.toDouble, tags)
      _    <- registry.gauge("graviton_disk_free_ratio", pct, tags)
      _    <- registry
                .gauge(
                  "graviton_disk_low",
                  if isLowDisk(snap) then 1.0 else 0.0,
                  tags,
                )
    yield ())
      .repeat(Schedule.spaced(interval))
      .unit
      .forkScoped
      .unit

object DiskMonitor:
  def make(path: Path, thresholds: DiskThresholds): ZLayer[MetricsRegistry, Nothing, DiskMonitor] =
    ZLayer.fromFunction((registry: MetricsRegistry) => new DiskMonitor(path, thresholds, registry))
