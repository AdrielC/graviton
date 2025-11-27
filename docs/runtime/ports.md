# Runtime Ports & Policies

Graviton's runtime layer defines abstract service interfaces (ports) that backend implementations fulfill. This design allows swapping storage engines without changing application logic.

## Core Ports

### BlobStore

Primary interface for blob storage:

```scala
trait BlobStore:
  /**
   * Store a blob with the given key
   */
  def put(key: BinaryKey, data: Chunk[Byte]): IO[StorageError, Unit]
  
  /**
   * Retrieve a blob by key
   */
  def get(key: BinaryKey): IO[StorageError, Chunk[Byte]]
  
  /**
   * Check if a blob exists
   */
  def exists(key: BinaryKey): IO[StorageError, Boolean]
  
  /**
   * Get blob metadata without downloading content
   */
  def stat(key: BinaryKey): IO[StorageError, BlobMetadata]
  
  /**
   * Delete a blob
   */
  def delete(key: BinaryKey): IO[StorageError, Unit]
  
  /**
   * List blobs with a given prefix
   */
  def list(prefix: String): ZStream[Any, StorageError, BinaryKey]

object BlobStore:
  val layer: ZLayer[BlobStoreConfig, ConfigError, BlobStore] = ???
```

### MutableObjectStore

Advanced interface with partial writes:

```scala
trait MutableObjectStore extends BlobStore:
  /**
   * Start a multipart upload session
   */
  def startMultipart(key: BinaryKey): IO[StorageError, UploadId]
  
  /**
   * Upload a part in a multipart upload
   */
  def uploadPart(
    uploadId: UploadId,
    partNumber: Int,
    data: Chunk[Byte]
  ): IO[StorageError, PartETag]
  
  /**
   * Complete a multipart upload
   */
  def completeMultipart(
    uploadId: UploadId,
    parts: Chunk[PartETag]
  ): IO[StorageError, Unit]
  
  /**
   * Abort a multipart upload
   */
  def abortMultipart(uploadId: UploadId): IO[StorageError, Unit]
  
  /**
   * Write a byte range to an existing blob
   */
  def putRange(
    key: BinaryKey,
    range: Span,
    data: Chunk[Byte]
  ): IO[StorageError, Unit]
  
  /**
   * Read a specific byte range
   */
  def getRange(
    key: BinaryKey,
    range: Span
  ): IO[StorageError, Chunk[Byte]]
```

### RangeTracker

Tracks which byte ranges have been written:

```scala
trait RangeTracker:
  /**
   * Mark a range as complete
   */
  def markComplete(key: BinaryKey, range: Span): IO[TrackerError, Unit]
  
  /**
   * Get all completed ranges for a blob
   */
  def getCompleted(key: BinaryKey): IO[TrackerError, RangeSet]
  
  /**
   * Get pending (incomplete) ranges
   */
  def getPending(key: BinaryKey, total: Span): IO[TrackerError, RangeSet]
  
  /**
   * Check if a blob is fully written
   */
  def isComplete(key: BinaryKey, expectedSize: Long): IO[TrackerError, Boolean]
  
  /**
   * Atomic compare-and-swap for ranges
   */
  def compareAndSet(
    key: BinaryKey,
    expected: RangeSet,
    update: RangeSet
  ): IO[TrackerError, Boolean]
```

### ManifestStore

Stores blob manifests:

```scala
trait ManifestStore:
  /**
   * Store a manifest
   */
  def put(manifest: BlobManifest): IO[ManifestError, Unit]
  
  /**
   * Retrieve a manifest by key
   */
  def get(key: BinaryKey): IO[ManifestError, BlobManifest]
  
  /**
   * List all manifests with a prefix
   */
  def list(prefix: String): ZStream[Any, ManifestError, BlobManifest]
  
  /**
   * Update manifest attributes
   */
  def updateAttributes(
    key: BinaryKey,
    attributes: BinaryAttributes
  ): IO[ManifestError, Unit]
```

### ReplicaIndex

Tracks replica placement:

```scala
trait ReplicaIndex:
  /**
   * Record a new replica
   */
  def addReplica(
    key: BinaryKey,
    sector: SectorId,
    ranges: RangeSet
  ): IO[ReplicaError, Unit]
  
  /**
   * Find replicas for a key
   */
  def findReplicas(key: BinaryKey): IO[ReplicaError, Chunk[ReplicaInfo]]
  
  /**
   * Find keys in a specific sector
   */
  def keysInSector(sector: SectorId): ZStream[Any, ReplicaError, BinaryKey]
  
  /**
   * Mark a replica as unhealthy
   */
  def markUnhealthy(
    key: BinaryKey,
    sector: SectorId,
    reason: String
  ): IO[ReplicaError, Unit]
  
  /**
   * Get replication status
   */
  def status(key: BinaryKey): IO[ReplicaError, ReplicationStatus]

final case class ReplicaInfo(
  key: BinaryKey,
  sector: SectorId,
  ranges: RangeSet,
  healthy: Boolean,
  lastVerified: Instant
)

final case class ReplicationStatus(
  targetReplicas: Int,
  actualReplicas: Int,
  healthyReplicas: Int,
  missingRanges: RangeSet
)
```

## Policies

### Replication Policy

Determines replica placement:

```scala
trait ReplicationPolicy:
  /**
   * Decide where to place replicas
   */
  def selectSectors(
    key: BinaryKey,
    count: Int,
    available: Chunk[SectorInfo]
  ): IO[PolicyError, Chunk[SectorId]]
  
  /**
   * Determine if rebalancing is needed
   */
  def needsRebalance(status: ReplicationStatus): Boolean
  
  /**
   * Select repair targets
   */
  def selectRepairTargets(
    key: BinaryKey,
    current: Chunk[ReplicaInfo],
    available: Chunk[SectorInfo]
  ): IO[PolicyError, Chunk[SectorId]]

object ReplicationPolicy:
  /**
   * Simple policy: random sector selection
   */
  val random: ReplicationPolicy = ???
  
  /**
   * Rack-aware policy: spread across racks
   */
  def rackAware(rackTopology: RackTopology): ReplicationPolicy = ???
  
  /**
   * Cost-optimized: prefer cheaper storage tiers
   */
  def costOptimized(pricing: StoragePricing): ReplicationPolicy = ???
```

### Retention Policy

Controls blob lifecycle:

```scala
trait RetentionPolicy:
  /**
   * Determine if a blob can be deleted
   */
  def canDelete(manifest: BlobManifest): IO[PolicyError, Boolean]
  
  /**
   * Determine if a blob should be archived
   */
  def shouldArchive(manifest: BlobManifest): IO[PolicyError, Boolean]
  
  /**
   * Get retention period
   */
  def retentionPeriod(manifest: BlobManifest): Duration

object RetentionPolicy:
  /**
   * Keep forever
   */
  val infinite: RetentionPolicy = ???
  
  /**
   * Time-based retention
   */
  def timeBasedDays: Int): RetentionPolicy = ???
  
  /**
   * Attribute-based retention
   */
  def attributeBased(rules: Chunk[RetentionRule]): RetentionPolicy = ???
```

### Spill Policy

Manages temporary storage for large uploads:

```scala
trait SpillPolicy:
  /**
   * Determine if data should spill to disk
   */
  def shouldSpill(bufferedBytes: Long): Boolean
  
  /**
   * Select spill directory
   */
  def selectSpillDir(available: Chunk[Path]): IO[PolicyError, Path]
  
  /**
   * Clean up spill files
   */
  def cleanup(spillFile: Path, age: Duration): IO[PolicyError, Unit]

final case class SpillConfig(
  threshold: Long = 64 * 1024 * 1024,  // 64 MB
  directory: Path = Path("/tmp/graviton-spill"),
  maxAge: Duration = 1.hour,
  maxTotal: Long = 10L * 1024 * 1024 * 1024  // 10 GB
)

object SpillPolicy:
  def fromConfig(config: SpillConfig): SpillPolicy = ???
```

### Constraint Policy

Enforces upload limits:

```scala
trait ConstraintPolicy:
  /**
   * Check if upload is allowed
   */
  def checkUpload(
    tenant: TenantId,
    size: Long,
    attributes: BinaryAttributes
  ): IO[ConstraintViolation, Unit]
  
  /**
   * Get remaining quota
   */
  def remainingQuota(tenant: TenantId): IO[PolicyError, Long]
  
  /**
   * Acquire upload permit
   */
  def acquirePermit(tenant: TenantId): IO[ConstraintViolation, Permit]
  
  /**
   * Release permit
   */
  def releasePermit(permit: Permit): UIO[Unit]

final case class ConstraintConfig(
  maxBlobSize: Long = 100L * 1024 * 1024 * 1024,  // 100 GB
  maxConcurrentUploads: Int = 10,
  quotaPerTenant: Long = 1L * 1024 * 1024 * 1024 * 1024,  // 1 TB
  rateLimit: Option[RateLimit] = None
)

final case class RateLimit(
  bytesPerSecond: Long,
  burstSize: Long
)
```

## Composing Ports

### Layered Architecture

```scala
object GravitonRuntime:
  /**
   * Build complete runtime stack
   */
  val layer: ZLayer[GravitonConfig, Throwable, GravitonRuntime] =
    ZLayer.make[GravitonRuntime](
      // Storage layer
      BlobStore.layer,
      ManifestStore.layer,
      RangeTracker.layer,
      ReplicaIndex.layer,
      
      // Policy layer
      ReplicationPolicy.layer,
      RetentionPolicy.layer,
      SpillPolicy.layer,
      ConstraintPolicy.layer,
      
      // Metrics
      MetricsRegistry.layer,
      
      // Configuration
      ZLayer.succeed(config)
    )
```

### Decorating Ports

Add cross-cutting concerns:

```scala
/**
 * Add metrics to any BlobStore
 */
def withMetrics(store: BlobStore, metrics: MetricsRegistry): BlobStore =
  new BlobStore:
    def put(key: BinaryKey, data: Chunk[Byte]) =
      metrics.counter("blob_store.put.count").increment *>
        metrics.histogram("blob_store.put.bytes").observe(data.size) *>
        store.put(key, data).timed.flatMap { case (duration, result) =>
          metrics.histogram("blob_store.put.duration").observe(duration.toMillis) *>
            ZIO.succeed(result)
        }
    
    def get(key: BinaryKey) =
      metrics.counter("blob_store.get.count").increment *>
        store.get(key).timed.flatMap { case (duration, result) =>
          metrics.histogram("blob_store.get.duration").observe(duration.toMillis) *>
            metrics.histogram("blob_store.get.bytes").observe(result.size) *>
            ZIO.succeed(result)
        }
    
    // ... other methods

/**
 * Add caching to any BlobStore
 */
def withCache(
  store: BlobStore,
  cache: Cache[BinaryKey, Chunk[Byte]]
): BlobStore =
  new BlobStore:
    def get(key: BinaryKey) =
      cache.get(key).flatMap {
        case Some(data) => ZIO.succeed(data)
        case None =>
          store.get(key).tap { data =>
            cache.put(key, data)
          }
      }
    
    def put(key: BinaryKey, data: Chunk[Byte]) =
      store.put(key, data) *> cache.invalidate(key)
    
    // ... other methods
```

## Testing Ports

### In-Memory Implementations

```scala
object InMemoryBlobStore:
  def make: UIO[BlobStore] =
    Ref.make(Map.empty[BinaryKey, Chunk[Byte]]).map { ref =>
      new BlobStore:
        def put(key: BinaryKey, data: Chunk[Byte]) =
          ref.update(_ + (key -> data))
        
        def get(key: BinaryKey) =
          ref.get.flatMap { map =>
            ZIO.fromOption(map.get(key))
              .orElseFail(StorageError.NotFound(key))
          }
        
        def exists(key: BinaryKey) =
          ref.get.map(_.contains(key))
        
        // ... other methods
    }
```

### Test Utilities

```scala
object PortTests:
  /**
   * Generic BlobStore contract tests
   */
  def blobStoreSpec(makeStore: UIO[BlobStore]): Spec[Any, Throwable] =
    suite("BlobStore")(
      test("put and get round-trip") {
        for {
          store <- makeStore
          key = BinaryKey.random
          data = Chunk.fromArray("test data".getBytes)
          _ <- store.put(key, data)
          retrieved <- store.get(key)
        } yield assertTrue(retrieved == data)
      },
      
      test("get non-existent key fails") {
        for {
          store <- makeStore
          key = BinaryKey.random
          result <- store.get(key).either
        } yield assertTrue(result.isLeft)
      },
      
      // ... more tests
    )
```

## See Also

- **[Backends](./backends.md)** — Concrete implementations
- **[Replication](./replication.md)** — Replica management
- **[Deployment](../ops/deployment.md)** — Production configuration
- **[Performance](../ops/performance.md)** — Optimization strategies

::: tip
Use dependency injection via ZLayer to swap implementations for testing and development.
:::
