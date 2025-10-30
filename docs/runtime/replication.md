# Replication & Repair

Graviton ensures data durability through configurable replication strategies and automated repair jobs.

## Replication Model

### Concepts

- **Sector**: A storage unit (e.g., a server, disk, or S3 bucket)
- **Replica**: A copy of a blob in a specific sector
- **Replica Set**: All replicas for a given blob key
- **Replication Factor**: Target number of replicas (typically 3)

```scala
final case class Sector(
  id: SectorId,
  capacity: Long,
  used: Long,
  healthy: Boolean,
  rack: Option[RackId],
  zone: Option[ZoneId]
)

final case class Replica(
  key: BinaryKey,
  sector: SectorId,
  ranges: RangeSet,
  healthy: Boolean,
  lastVerified: Instant,
  checksum: Option[Digest]
)
```

## Replication Strategies

### Simple Replication

Replicate to N sectors:

```scala
final case class SimpleReplicationStrategy(
  factor: Int = 3
) extends ReplicationStrategy:
  
  def replicate(
    key: BinaryKey,
    data: Chunk[Byte],
    sectors: Chunk[Sector]
  ): IO[ReplicationError, Chunk[Replica]] =
    for {
      // Select target sectors
      targets <- selectSectors(key, factor, sectors)
      
      // Write to all sectors in parallel
      replicas <- ZIO.foreachPar(targets) { sector =>
        for {
          store <- sectorStore(sector)
          _ <- store.put(key, data)
          replica = Replica(
            key = key,
            sector = sector.id,
            ranges = RangeSet.full(data.size),
            healthy = true,
            lastVerified = Instant.now
          )
          _ <- replicaIndex.addReplica(replica)
        } yield replica
      }
    } yield replicas
  
  private def selectSectors(
    key: BinaryKey,
    count: Int,
    available: Chunk[Sector]
  ): IO[ReplicationError, Chunk[Sector]] =
    // Filter healthy sectors with capacity
    val eligible = available.filter(s => s.healthy && s.capacity - s.used > 0)
    
    if eligible.size < count then
      ZIO.fail(ReplicationError.InsufficientSectors)
    else
      // Random selection
      ZIO.succeed(Random.shuffle(eligible).take(count))
```

### Rack-Aware Replication

Spread replicas across racks for fault tolerance:

```scala
final case class RackAwareStrategy(
  factor: Int = 3,
  minRacks: Int = 2
) extends ReplicationStrategy:
  
  def selectSectors(
    key: BinaryKey,
    count: Int,
    available: Chunk[Sector]
  ): IO[ReplicationError, Chunk[Sector]] =
    for {
      // Group sectors by rack
      byRack <- ZIO.succeed(available.groupBy(_.rack))
      
      // Ensure minimum rack diversity
      _ <- ZIO.when(byRack.size < minRacks) {
        ZIO.fail(ReplicationError.InsufficientRackDiversity)
      }
      
      // Select sectors from different racks
      selected <- spreadAcrossRacks(byRack, count)
    } yield selected
  
  private def spreadAcrossRacks(
    byRack: Map[Option[RackId], Chunk[Sector]],
    count: Int
  ): IO[ReplicationError, Chunk[Sector]] =
    // Round-robin selection from racks
    val racks = byRack.values.toList
    val selected = (0 until count).map { i =>
      val rack = racks(i % racks.size)
      rack(i / racks.size % rack.size)
    }
    ZIO.succeed(Chunk.fromIterable(selected))
```

### Zone-Aware Replication

Spread across availability zones:

```scala
final case class ZoneAwareStrategy(
  factor: Int = 3,
  minZones: Int = 2
) extends ReplicationStrategy:
  
  def selectSectors(
    key: BinaryKey,
    count: Int,
    available: Chunk[Sector]
  ): IO[ReplicationError, Chunk[Sector]] =
    val byZone = available.groupBy(_.zone)
    
    // Prefer spreading across zones
    val primary = selectFromZone(byZone(Some(Zone.Primary)), 1)
    val secondary = selectFromZone(byZone(Some(Zone.Secondary)), 1)
    val tertiary = selectFromZone(byZone(Some(Zone.Tertiary)), 1)
    
    ZIO.succeed(primary ++ secondary ++ tertiary)
```

## Replication Lifecycle

### Write Path

```scala
def writeWithReplication(
  key: BinaryKey,
  data: Chunk[Byte],
  strategy: ReplicationStrategy
): IO[WriteError, WriteResult] =
  for {
    // Get available sectors
    sectors <- sectorRegistry.listHealthy
    
    // Primary write
    primarySector <- strategy.selectPrimary(sectors)
    primaryStore <- sectorStore(primarySector)
    _ <- primaryStore.put(key, data)
    
    // Async replication to replicas
    replicaFiber <- strategy.replicate(key, data, sectors.filter(_ != primarySector))
      .forkDaemon
    
    // Wait for quorum (e.g., 2 out of 3)
    _ <- replicaFiber.join.timeout(30.seconds)
      .flatMap {
        case Some(replicas) if replicas.size >= 2 => ZIO.unit
        case _ => ZIO.logWarning("Replication quorum not reached")
      }
    
    result = WriteResult(
      key = key,
      size = data.size,
      replicas = ??? // track replicas
    )
  } yield result
```

### Read Path with Fallback

```scala
def readWithFallback(
  key: BinaryKey
): IO[ReadError, Chunk[Byte]] =
  for {
    // Find all replicas
    replicas <- replicaIndex.findReplicas(key)
    
    // Sort by preference (healthy, local, recent)
    ordered <- orderReplicas(replicas)
    
    // Try replicas until success
    data <- tryReplicas(key, ordered)
      .someOrFail(ReadError.AllReplicasFailed)
  } yield data

private def tryReplicas(
  key: BinaryKey,
  replicas: Chunk[Replica]
): ZIO[Any, Nothing, Option[Chunk[Byte]]] =
  replicas.foldLeft(ZIO.succeed(Option.empty[Chunk[Byte]])) { (acc, replica) =>
    acc.flatMap {
      case Some(data) => ZIO.succeed(Some(data))
      case None =>
        sectorStore(replica.sector).get(key)
          .map(Some(_))
          .catchAll { error =>
            ZIO.logWarning(s"Failed to read from sector ${replica.sector}: $error") *>
              ZIO.succeed(None)
          }
    }
  }
```

## Repair & Healing

### Gap Detection

```scala
def detectGaps: ZStream[Any, ReplicationError, RepairJob] =
  replicaIndex.allKeys
    .mapZIO { key =>
      for {
        replicas <- replicaIndex.findReplicas(key)
        status <- analyzeReplicationStatus(key, replicas)
        job <- if status.needsRepair then
          ZIO.some(RepairJob(key, status))
        else
          ZIO.none
      } yield job
    }
    .collectSome

final case class RepairJob(
  key: BinaryKey,
  status: ReplicationStatus
)

final case class ReplicationStatus(
  key: BinaryKey,
  targetReplicas: Int,
  actualReplicas: Int,
  healthyReplicas: Int,
  missingRanges: RangeSet,
  unhealthyReplicas: Chunk[Replica]
):
  def needsRepair: Boolean =
    healthyReplicas < targetReplicas || missingRanges.nonEmpty
```

### Repair Execution

```scala
def executeRepair(job: RepairJob): IO[RepairError, RepairResult] =
  for {
    // Find a healthy source replica
    source <- replicaIndex.findReplicas(job.key)
      .map(_.filter(_.healthy).headOption)
      .someOrFail(RepairError.NoHealthySource)
    
    // Read data from source
    sourceStore <- sectorStore(source.sector)
    data <- sourceStore.get(job.key)
    
    // Select new target sectors
    sectors <- sectorRegistry.listHealthy
    targets <- replicationStrategy.selectRepairTargets(
      job.key,
      job.status.actualReplicas,
      sectors
    )
    
    // Write to new sectors
    newReplicas <- ZIO.foreachPar(targets) { sector =>
      for {
        store <- sectorStore(sector)
        _ <- store.put(job.key, data)
        replica = Replica(
          key = job.key,
          sector = sector.id,
          ranges = RangeSet.full(data.size),
          healthy = true,
          lastVerified = Instant.now
        )
        _ <- replicaIndex.addReplica(replica)
      } yield replica
    }
    
    result = RepairResult(
      key = job.key,
      repairedReplicas = newReplicas.size,
      duration = ???
    )
  } yield result
```

### Continuous Repair Loop

```scala
def repairDaemon(interval: Duration): ZIO[Any, Nothing, Nothing] =
  detectGaps
    .mapZIOPar(4)(executeRepair)  // Parallel repair jobs
    .tap { result =>
      metrics.counter("repair.jobs.completed").increment *>
        ZIO.logInfo(s"Repaired ${result.key}: ${result.repairedReplicas} replicas")
    }
    .catchAll { error =>
      metrics.counter("repair.jobs.failed").increment *>
        ZIO.logError(s"Repair failed: $error")
    }
    .repeat(Schedule.spaced(interval))
    .unit
    .forever

// Start as daemon
val repairService = repairDaemon(5.minutes).forkDaemon
```

## Verification & Scrubbing

### Checksum Verification

```scala
def verifyScrub: ZStream[Any, VerificationError, ScrubResult] =
  replicaIndex.allReplicas
    .mapZIOPar(8) { replica =>
      for {
        store <- sectorStore(replica.sector)
        data <- store.get(replica.key)
        
        // Compute checksum
        computed <- HashAlgo.SHA256.hash(data.toArray)
        
        // Compare with stored checksum
        match <- replica.checksum match
          case Some(expected) => ZIO.succeed(computed == expected)
          case None => ZIO.succeed(true)  // No checksum to verify
        
        // Mark unhealthy if mismatch
        _ <- ZIO.when(!match) {
          replicaIndex.markUnhealthy(replica.key, replica.sector, "Checksum mismatch")
        }
        
        result = ScrubResult(
          key = replica.key,
          sector = replica.sector,
          verified = match,
          checksum = computed
        )
      } yield result
    }

// Periodic scrubbing
def scrubSchedule = Schedule.spaced(7.days) && Schedule.recurWhileEquals(true)

val scrubService = verifyScrubForever { result =>
  if !result.verified then
    ZIO.logWarning(s"Checksum mismatch: ${result.key} in ${result.sector}")
  else
    ZIO.unit
}.forkDaemon
```

## Rebalancing

### Load-Based Rebalancing

```scala
def rebalance: IO[RebalanceError, RebalanceResult] =
  for {
    sectors <- sectorRegistry.listAll
    
    // Identify overloaded and underloaded sectors
    overloaded = sectors.filter(s => s.used.toDouble / s.capacity > 0.9)
    underloaded = sectors.filter(s => s.used.toDouble / s.capacity < 0.5)
    
    // Move replicas from overloaded to underloaded
    moves <- ZIO.foreachPar(overloaded) { source =>
      rebalanceSector(source, underloaded)
    }
    
    result = RebalanceResult(
      movedReplicas = moves.sum,
      duration = ???
    )
  } yield result

private def rebalanceSector(
  source: Sector,
  targets: Chunk[Sector]
): IO[RebalanceError, Int] =
  for {
    // Find replicas in source sector
    keys <- replicaIndex.keysInSector(source.id).runCollect
    
    // Select replicas to move (keep at least 1)
    toMove = keys.take((keys.size * 0.3).toInt)  // Move 30%
    
    // Move each replica
    moved <- ZIO.foreachPar(toMove) { key =>
      moveReplica(key, source, targets.head)
    }
  } yield moved.size

private def moveReplica(
  key: BinaryKey,
  from: Sector,
  to: Sector
): IO[RebalanceError, Unit] =
  for {
    // Read from source
    sourceStore <- sectorStore(from)
    data <- sourceStore.get(key)
    
    // Write to target
    targetStore <- sectorStore(to)
    _ <- targetStore.put(key, data)
    
    // Update index
    _ <- replicaIndex.addReplica(Replica(
      key = key,
      sector = to.id,
      ranges = RangeSet.full(data.size),
      healthy = true,
      lastVerified = Instant.now
    ))
    
    // Remove from source (after successful write)
    _ <- sourceStore.delete(key)
    _ <- replicaIndex.removeReplica(key, from.id)
  } yield ()
```

## Monitoring

### Replication Metrics

```scala
object ReplicationMetrics:
  val replicationFactor = metrics.gauge("replication.factor")
  val underReplicated = metrics.gauge("replication.under_replicated")
  val overReplicated = metrics.gauge("replication.over_replicated")
  val healthyReplicas = metrics.gauge("replication.healthy_replicas")
  val unhealthyReplicas = metrics.gauge("replication.unhealthy_replicas")
  val repairQueueSize = metrics.gauge("repair.queue_size")
  val repairRate = metrics.counter("repair.rate")

def updateMetrics: UIO[Unit] =
  for {
    stats <- replicaIndex.globalStats
    _ <- replicationFactor.set(stats.avgReplicationFactor)
    _ <- underReplicated.set(stats.underReplicatedCount)
    _ <- overReplicated.set(stats.overReplicatedCount)
    _ <- healthyReplicas.set(stats.healthyReplicasCount)
    _ <- unhealthyReplicas.set(stats.unhealthyReplicasCount)
  } yield ()

// Update every minute
val metricsUpdater = updateMetrics
  .repeat(Schedule.spaced(1.minute))
  .forkDaemon
```

## Configuration

```hocon
graviton {
  replication {
    factor = 3
    min-replicas = 2
    
    strategy = "rack-aware"  # or "simple", "zone-aware"
    
    # Rack awareness
    min-racks = 2
    
    # Zone awareness  
    min-zones = 2
    
    # Repair
    repair {
      enabled = true
      interval = 5 minutes
      parallel-jobs = 4
      batch-size = 100
    }
    
    # Scrubbing
    scrub {
      enabled = true
      interval = 7 days
      parallel-reads = 8
    }
    
    # Rebalancing
    rebalance {
      enabled = true
      threshold = 0.1  # 10% utilization difference
      max-moves-per-run = 100
    }
  }
}
```

## See Also

- **[Ports & Policies](./ports)** — ReplicaIndex interface
- **[Backends](./backends)** — Storage implementations
- **[Performance](../ops/performance)** — Replication overhead
- **[Deployment](../ops/deployment)** — Multi-node setup

::: warning
Always test replication failures before deploying to production!
:::
