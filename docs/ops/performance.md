# Performance Tuning

Optimize Graviton for your workload.

## Benchmarking

### Baseline Metrics

Measure before optimizing:

```scala
import zio.*
import zio.metrics.*

def benchmarkUpload(size: Long, count: Int): ZIO[BlobStore, Throwable, Duration] =
  for {
    start <- Clock.instant
    data = Chunk.fill(size.toInt)(0.toByte)
    _ <- ZIO.foreachPar(1 to count) { i =>
      val key = BinaryKey.random
      blobStore.put(key, data)
    }
    end <- Clock.instant
    duration = Duration.between(start, end)
    throughput = (size * count) / duration.getSeconds
    _ <- Console.printLine(s"Throughput: ${throughput / 1024 / 1024} MB/s")
  } yield duration
```

### Key Metrics

| Metric | Target | Good | Needs Work |
|--------|--------|------|------------|
| Upload throughput | >500 MB/s | >200 MB/s | <100 MB/s |
| Download throughput | >800 MB/s | >400 MB/s | <200 MB/s |
| Latency (p50) | <10ms | <50ms | >100ms |
| Latency (p99) | <100ms | <200ms | >500ms |
| CPU usage | <60% | <80% | >90% |
| Memory usage | <70% | <85% | >95% |

## Chunking Performance

### Algorithm Selection

```scala
// Benchmark different chunkers
def benchmarkChunkers(data: Chunk[Byte]): ZIO[Any, Nothing, Unit] =
  for {
    // Fixed-size (baseline)
    fixedDuration <- ZIO.succeed(System.nanoTime()).flatMap { start =>
      FixedChunker(1.MB).chunk(data) *>
        ZIO.succeed(Duration.ofNanos(System.nanoTime() - start))
    }
    
    // FastCDC
    fastcdcDuration <- ZIO.succeed(System.nanoTime()).flatMap { start =>
      FastCDC.chunker(FastCDCConfig()).chunk(data) *>
        ZIO.succeed(Duration.ofNanos(System.nanoTime() - start))
    }
    
    _ <- Console.printLine(s"Fixed: ${fixedDuration.toMillis}ms")
    _ <- Console.printLine(s"FastCDC: ${fastcdcDuration.toMillis}ms")
    _ <- Console.printLine(s"Overhead: ${(fastcdcDuration.toMillis - fixedDuration.toMillis) * 100 / fixedDuration.toMillis}%")
  } yield ()
```

### Chunk Size Tuning

```hocon
graviton {
  chunking {
    # Larger chunks = faster, worse dedup
    # Smaller chunks = slower, better dedup
    
    # High throughput workload
    high-throughput {
      min-size = 1MB
      avg-size = 4MB
      max-size = 16MB
    }
    
    # Balanced
    balanced {
      min-size = 256KB
      avg-size = 1MB
      max-size = 4MB
    }
    
    # Maximum dedup
    max-dedup {
      min-size = 64KB
      avg-size = 256KB
      max-size = 1MB
    }
  }
}
```

## Backend Optimization

### PostgreSQL

```sql
-- Increase shared buffers
ALTER SYSTEM SET shared_buffers = '4GB';

-- Increase work memory
ALTER SYSTEM SET work_mem = '64MB';

-- Increase maintenance work memory
ALTER SYSTEM SET maintenance_work_mem = '512MB';

-- Enable parallel queries
ALTER SYSTEM SET max_parallel_workers_per_gather = 4;

-- Increase checkpoint timeout
ALTER SYSTEM SET checkpoint_timeout = '15min';

-- WAL settings
ALTER SYSTEM SET wal_buffers = '16MB';
ALTER SYSTEM SET max_wal_size = '4GB';

SHOW ALL;  -- Verify settings
```

**Indexes:**
```sql
-- Add index on created_at for time-range queries
CREATE INDEX CONCURRENTLY idx_blobs_created_at ON blobs(created_at);

-- Add partial index for unhealthy replicas
CREATE INDEX CONCURRENTLY idx_replicas_unhealthy 
  ON replicas(key, sector_id) 
  WHERE NOT healthy;

-- Analyze tables regularly
ANALYZE blobs;
ANALYZE replicas;
ANALYZE manifests;
```

### S3

```hocon
graviton {
  s3 {
    # Increase part size for large files
    multipart {
      part-size = 10MB  # Min 5MB, max 5GB
      max-parts = 10000
    }
    
    # Connection pool
    connection-pool {
      max-connections = 100
      connection-timeout = 30s
      socket-timeout = 60s
    }
    
    # Enable transfer acceleration
    transfer-acceleration = true
    
    # Use path-style access for better routing
    path-style-access = false
  }
}
```

### RocksDB

```hocon
graviton {
  rocksdb {
    # Increase block cache
    block-cache-size = 1GB
    
    # Increase write buffer
    write-buffer-size = 128MB
    max-write-buffer-number = 4
    
    # Compression
    compression = "lz4"  # Fast compression
    bottommost-compression = "zstd"  # Better compression for old data
    
    # Bloom filters
    bloom-filter-bits-per-key = 10
    
    # Compaction
    max-background-jobs = 8
    level0-file-num-compaction-trigger = 2
    level0-slowdown-writes-trigger = 20
    level0-stop-writes-trigger = 24
    
    # Block size
    block-size = 16KB  # Larger blocks = better compression, worse cache
  }
}
```

## JVM Tuning

### GC Configuration

```bash
# G1GC (recommended for most workloads)
JAVA_OPTS="
  -Xmx8g
  -Xms8g
  -XX:+UseG1GC
  -XX:MaxGCPauseMillis=200
  -XX:G1HeapRegionSize=16M
  -XX:InitiatingHeapOccupancyPercent=45
  -XX:+ParallelRefProcEnabled
"

# ZGC (for ultra-low latency)
JAVA_OPTS="
  -Xmx16g
  -Xms16g
  -XX:+UseZGC
  -XX:+ZGenerational
  -XX:ZCollectionInterval=5
"

# Shenandoah (alternative low-pause GC)
JAVA_OPTS="
  -Xmx8g
  -Xms8g
  -XX:+UseShenandoahGC
  -XX:ShenandoahGCHeuristics=adaptive
"
```

### JIT Compilation

```bash
JAVA_OPTS="
  $JAVA_OPTS
  -XX:+TieredCompilation
  -XX:TieredStopAtLevel=1  # Fast startup
  -XX:CICompilerCount=4
"
```

### Monitoring

```bash
JAVA_OPTS="
  $JAVA_OPTS
  -XX:+UnlockDiagnosticVMOptions
  -XX:+PrintGCDetails
  -XX:+PrintGCDateStamps
  -Xlog:gc*:file=/var/log/graviton/gc.log:time,level,tags
  -XX:+HeapDumpOnOutOfMemoryError
  -XX:HeapDumpPath=/var/log/graviton/heap_dump.hprof
"
```

## Network Optimization

### TCP Tuning

```bash
# Increase TCP buffer sizes
sudo sysctl -w net.core.rmem_max=16777216
sudo sysctl -w net.core.wmem_max=16777216
sudo sysctl -w net.ipv4.tcp_rmem="4096 87380 16777216"
sudo sysctl -w net.ipv4.tcp_wmem="4096 65536 16777216"

# Enable TCP window scaling
sudo sysctl -w net.ipv4.tcp_window_scaling=1

# Increase connection tracking
sudo sysctl -w net.netfilter.nf_conntrack_max=1000000

# Fast connection reuse
sudo sysctl -w net.ipv4.tcp_tw_reuse=1

# Persist changes
sudo sysctl -p
```

### HTTP/2 & gRPC

```hocon
graviton {
  server {
    grpc {
      # Enable keepalive
      keepalive-time = 30s
      keepalive-timeout = 10s
      
      # Increase max message size
      max-message-size = 100MB
      
      # Connection limits
      max-concurrent-streams = 100
      max-connection-idle = 5m
    }
    
    http {
      # HTTP/2 settings
      enable-http2 = true
      max-concurrent-streams = 100
      
      # Connection pool
      connection-pool-size = 100
    }
  }
}
```

## Parallelism

### Upload Parallelism

```scala
// Bad: Sequential uploads
def uploadSequential(blobs: Chunk[(BinaryKey, Chunk[Byte])]): ZIO[BlobStore, Throwable, Unit] =
  ZIO.foreach(blobs) { case (key, data) =>
    blobStore.put(key, data)
  }.unit

// Good: Parallel uploads
def uploadParallel(blobs: Chunk[(BinaryKey, Chunk[Byte])]): ZIO[BlobStore, Throwable, Unit] =
  ZIO.foreachPar(blobs) { case (key, data) =>
    blobStore.put(key, data)
  }.unit.withParallelism(8)  // Limit parallelism

// Better: Batched parallel uploads
def uploadBatched(blobs: Chunk[(BinaryKey, Chunk[Byte])]): ZIO[BlobStore, Throwable, Unit] =
  ZStream.fromChunk(blobs)
    .grouped(100)  // Batch size
    .mapZIOPar(8) { batch =>  // 8 batches in parallel
      ZIO.foreachPar(batch) { case (key, data) =>
        blobStore.put(key, data)
      }.unit
    }
    .runDrain
```

### Fiber Management

```scala
// Control fiber pool size
val uploadService = ZIO.foreachPar(uploads) { upload =>
  processUpload(upload)
}.withParallelism(Runtime.defaultExecutor.asExecutionContext.getPoolSize)

// Use bounded queues
def boundedUploadQueue(capacity: Int = 1000): ZIO[Scope, Nothing, Queue[Upload]] =
  Queue.bounded[Upload](capacity)
```

## Caching

### In-Memory Cache

```scala
import zio.cache.*

// Create cache
val blobCache: ZIO[Scope, Nothing, Cache[BinaryKey, Throwable, Chunk[Byte]]] =
  Cache.make(
    capacity = 1000,
    timeToLive = 5.minutes,
    lookup = Lookup(blobStore.get)
  )

// Use cache
def getCached(key: BinaryKey): ZIO[Any, Throwable, Chunk[Byte]] =
  blobCache.flatMap(_.get(key))
```

### CDN Integration

```hocon
graviton {
  cdn {
    enabled = true
    provider = "cloudflare"  # or "cloudfront", "fastly"
    
    # Cache-Control headers
    cache-control {
      immutable-content = "public, max-age=31536000, immutable"
      mutable-content = "public, max-age=3600"
    }
    
    # Purge on update
    purge-on-update = true
  }
}
```

## Profiling

### CPU Profiling

```bash
# Using async-profiler
java -agentpath:/path/to/libasyncProfiler.so=start,event=cpu,file=profile.html \
  -jar graviton-server.jar

# JFR (Java Flight Recorder)
java -XX:StartFlightRecording=duration=60s,filename=recording.jfr \
  -jar graviton-server.jar

# Analyze JFR
jfr print --events CPUSample recording.jfr
```

### Memory Profiling

```bash
# Heap dump
jmap -dump:live,format=b,file=heap.bin <pid>

# Analyze with Eclipse MAT
mat heap.bin

# JFR memory profiling
jfr print --events ObjectAllocation recording.jfr
```

## Load Testing

### Gatling Script

```scala
import io.gatling.core.Predef.*
import io.gatling.http.Predef.*

class GravitonLoadTest extends Simulation {
  val httpProtocol = http
    .baseUrl("http://localhost:8080")
    .acceptHeader("application/json")
  
  val upload = scenario("Upload")
    .exec(
      http("upload blob")
        .post("/api/v1/blobs")
        .header("Content-Type", "application/octet-stream")
        .body(RawFileBody("test-file.bin"))
        .check(status.is(200))
    )
  
  setUp(
    upload.inject(
      rampUsersPerSec(10).to(100).during(60.seconds),
      constantUsersPerSec(100).during(300.seconds)
    )
  ).protocols(httpProtocol)
}
```

## See Also

- **[Deployment](./deployment)** — Production setup
- **[Backends](../runtime/backends)** — Storage configuration
- **[Chunking](../ingest/chunking)** — Algorithm selection

::: tip
Always profile with realistic workloads before optimizing!
:::
