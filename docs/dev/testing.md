# Testing Guide

Comprehensive testing strategies for Graviton.

## Test Philosophy

- **Test behavior, not implementation**
- **Keep tests fast and deterministic**
- **Use property-based testing for algorithms**
- **Integration tests for backend interactions**
- **Performance tests for critical paths**

## Running Tests

### All Tests

```bash
# Without TestContainers (faster)
TESTCONTAINERS=0 ./sbt test

# With TestContainers (full integration)
TESTCONTAINERS=1 ./sbt test

# Specific module
./sbt "core/test"

# Specific test suite
./sbt "testOnly graviton.core.BinaryKeySpec"

# With coverage
./sbt clean coverage test coverageReport
```

### Watch Mode

```bash
# Continuous testing
./sbt ~test

# Specific module
./sbt "~core/test"
```

## Unit Tests

### ZIO Test Basics

```scala
import zio.test.*
import zio.test.Assertion.*

object CalculatorSpec extends ZIOSpecDefault {
  def spec = suite("Calculator")(
    test("addition") {
      val result = 2 + 2
      assertTrue(result == 4)
    },
    
    test("ZIO effects") {
      for {
        result <- ZIO.succeed(2 + 2)
      } yield assertTrue(result == 4)
    },
    
    test("async operations") {
      for {
        fiber <- ZIO.succeed(2 + 2).fork
        result <- fiber.join
      } yield assertTrue(result == 4)
    }
  )
}
```

### Testing Pure Functions

```scala
object RangeSetSpec extends ZIOSpecDefault {
  def spec = suite("RangeSet")(
    test("union combines ranges") {
      val rs1 = RangeSet.single(Interval(Bound(0), Bound(10)))
      val rs2 = RangeSet.single(Interval(Bound(5), Bound(15)))
      val result = rs1.union(rs2)
      
      assertTrue(
        result.intervals.size == 1,
        result.intervals.head == Interval(Bound(0), Bound(15))
      )
    },
    
    test("intersection finds overlap") {
      val rs1 = RangeSet.single(Interval(Bound(0), Bound(10)))
      val rs2 = RangeSet.single(Interval(Bound(5), Bound(15)))
      val result = rs1.intersect(rs2)
      
      assertTrue(
        result.intervals.size == 1,
        result.intervals.head == Interval(Bound(5), Bound(10))
      )
    }
  )
}
```

### Property-Based Testing

```scala
import zio.test.Gen

object RangeSetPropertySpec extends ZIOSpecDefault {
  val boundGen: Gen[Any, Bound] = 
    Gen.long(0, 10000).map(Bound(_))
  
  val intervalGen: Gen[Any, Interval] =
    for {
      start <- boundGen
      length <- Gen.long(1, 1000)
      end = Bound(start.value + length)
    } yield Interval(start, end)
  
  val rangeSetGen: Gen[Any, RangeSet] =
    Gen.chunkOf(intervalGen).map(RangeSet(_))
  
  def spec = suite("RangeSet Properties")(
    test("union is commutative") {
      check(rangeSetGen, rangeSetGen) { (rs1, rs2) =>
        val union1 = rs1.union(rs2)
        val union2 = rs2.union(rs1)
        assertTrue(union1 == union2)
      }
    },
    
    test("intersection is associative") {
      check(rangeSetGen, rangeSetGen, rangeSetGen) { (rs1, rs2, rs3) =>
        val result1 = (rs1.intersect(rs2)).intersect(rs3)
        val result2 = rs1.intersect(rs2.intersect(rs3))
        assertTrue(result1 == result2)
      }
    },
    
    test("union with empty is identity") {
      check(rangeSetGen) { rs =>
        val result = rs.union(RangeSet.empty)
        assertTrue(result == rs)
      }
    }
  )
}
```

## Integration Tests

### In-Memory Backends

```scala
object InMemoryBlobStoreSpec extends ZIOSpecDefault {
  def spec = suite("InMemoryBlobStore")(
    test("put and get round-trip") {
      for {
        store <- InMemoryBlobStore.make
        key = BinaryKey.random
        data = Chunk.fromArray("test data".getBytes)
        _ <- store.put(key, data)
        retrieved <- store.get(key)
      } yield assertTrue(retrieved == data)
    },
    
    test("get non-existent key fails") {
      for {
        store <- InMemoryBlobStore.make
        key = BinaryKey.random
        result <- store.get(key).either
      } yield assertTrue(result.isLeft)
    },
    
    test("delete removes blob") {
      for {
        store <- InMemoryBlobStore.make
        key = BinaryKey.random
        data = Chunk.fromArray("test".getBytes)
        _ <- store.put(key, data)
        _ <- store.delete(key)
        exists <- store.exists(key)
      } yield assertTrue(!exists)
    }
  )
}
```

### TestContainers

```scala
import com.dimafeng.testcontainers.PostgreSQLContainer
import zio.test.*

object PostgresBlobStoreSpec extends ZIOSpecDefault {
  val containerLayer = ZLayer.scoped {
    ZIO.acquireRelease(
      ZIO.attempt {
        val container = PostgreSQLContainer("postgres:18")
        container.start()
        container
      }
    )(container => ZIO.succeed(container.stop()))
  }
  
  val configLayer = ZLayer.fromZIO {
    for {
      container <- ZIO.service[PostgreSQLContainer]
    } yield PostgresConfig(
      url = container.jdbcUrl,
      username = container.username,
      password = container.password
    )
  }
  
  def spec = suite("PostgresBlobStore")(
    test("put and get") {
      for {
        store <- ZIO.service[PostgresBlobStore]
        key = BinaryKey.random
        data = Chunk.fromArray("test".getBytes)
        _ <- store.put(key, data)
        retrieved <- store.get(key)
      } yield assertTrue(retrieved == data)
    }
  ).provide(
    containerLayer,
    configLayer,
    PostgresBlobStore.layer
  ) @@ TestAspect.withLiveEnvironment
}
```

### Mocking

```scala
trait BlobStore {
  def put(key: BinaryKey, data: Chunk[Byte]): IO[StorageError, Unit]
  def get(key: BinaryKey): IO[StorageError, Chunk[Byte]]
}

object MockBlobStore {
  def failingGet(error: StorageError): BlobStore = new BlobStore {
    def put(key: BinaryKey, data: Chunk[Byte]) = ZIO.unit
    def get(key: BinaryKey) = ZIO.fail(error)
  }
  
  def delayed(delay: Duration): BlobStore = new BlobStore {
    def put(key: BinaryKey, data: Chunk[Byte]) =
      ZIO.sleep(delay) *> ZIO.unit
    def get(key: BinaryKey) =
      ZIO.sleep(delay) *> ZIO.succeed(Chunk.empty)
  }
}

test("retry on storage failure") {
  val mockStore = MockBlobStore.failingGet(StorageError.Unavailable)
  
  // Test retry logic with mock
  val result = uploadWithRetry(key, data)
    .provide(ZLayer.succeed(mockStore))
    .either
  
  assertTrue(result.isLeft)
}
```

## Test Fixtures

### Reusable Test Data

```scala
object TestFixtures {
  val smallBlob: Chunk[Byte] = 
    Chunk.fromArray("small test data".getBytes)
  
  val mediumBlob: Chunk[Byte] =
    Chunk.fromArray(Random.nextBytes(1024 * 1024))  // 1 MB
  
  val largeBlob: Chunk[Byte] =
    Chunk.fromArray(Random.nextBytes(100 * 1024 * 1024))  // 100 MB
  
  def randomKey: BinaryKey =
    BinaryKey.fromBytes(Random.nextBytes(32))
  
  def sampleManifest: BlobManifest =
    BlobManifest(
      key = randomKey,
      size = 1024,
      entries = Chunk(
        BlockEntry(randomKey, Span(0, 1024))
      )
    )
}
```

### Shared Test Layers

```scala
object TestLayers {
  val inMemoryBlobStore: ZLayer[Any, Nothing, BlobStore] =
    ZLayer.fromZIO(InMemoryBlobStore.make)
  
  val mockMetrics: ZLayer[Any, Nothing, MetricsRegistry] =
    ZLayer.succeed(NoOpMetricsRegistry)
  
  val testConfig: ZLayer[Any, Nothing, GravitonConfig] =
    ZLayer.succeed(GravitonConfig.default)
  
  val fullStack: ZLayer[Any, Nothing, BlobStore with MetricsRegistry with GravitonConfig] =
    inMemoryBlobStore ++ mockMetrics ++ testConfig
}

// Use in tests
test("upload with full stack") {
  for {
    store <- ZIO.service[BlobStore]
    _ <- store.put(key, data)
  } yield assertTrue(true)
}.provide(TestLayers.fullStack)
```

### In-memory stores

The runtime module ships `InMemoryBlockStore` and `InMemoryBlobStore` implementations so tests do not need S3 or PostgreSQL:

```scala
import graviton.runtime.stores.{InMemoryBlockStore, InMemoryBlobStore}
import zio.*

val blockStoreLayer: ULayer[BlockStore] = InMemoryBlockStore.layer
val blobStoreLayer:  ULayer[BlobStore]  = InMemoryBlobStore.layer
```

Because both stores use `Ref`-backed maps, they deduplicate data, materialize manifests, and expose `BlobStat`s just like the production traits, making them ideal for unit, integration, and CLI smoke tests.

## Performance Tests

### Benchmarking

```scala
import zio.test.TestAspect.*

test("upload throughput") {
  val data = Chunk.fromArray(Random.nextBytes(10 * 1024 * 1024))  // 10 MB
  val iterations = 100
  
  for {
    start <- Clock.instant
    _ <- ZIO.foreachPar(1 to iterations) { i =>
      blobStore.put(BinaryKey.random, data)
    }
    end <- Clock.instant
    duration = Duration.between(start, end)
    throughput = (data.size * iterations) / duration.getSeconds
    _ <- ZIO.logInfo(s"Throughput: ${throughput / 1024 / 1024} MB/s")
  } yield assertTrue(throughput > 100 * 1024 * 1024)  // > 100 MB/s
} @@ timeout(60.seconds) @@ flaky
```

### Load Testing

```scala
test("concurrent uploads") {
  val concurrency = 100
  val data = Chunk.fromArray("test".getBytes)
  
  for {
    results <- ZIO.foreachPar(1 to concurrency) { i =>
      blobStore.put(BinaryKey.random, data).either
    }
    successful = results.count(_.isRight)
    _ <- ZIO.logInfo(s"$successful/$concurrency succeeded")
  } yield assertTrue(successful >= concurrency * 0.95)  // 95% success rate
} @@ timeout(30.seconds)
```

## Test Aspects

### Common Aspects

```scala
import zio.test.TestAspect.*
import zio.*

// Run in parallel
suite("Fast tests")(
  test1,
  test2,
  test3
) @@ parallel

// Timeout
test("long operation") {
  ZIO.sleep(1.second) *> ZIO.succeed(assertTrue(true))
} @@ timeout(10.seconds)

// Retry flaky tests
test("network operation") {
  ZIO.succeed(assertTrue(true))
} @@ flaky @@ retry(3)

// Ignore in CI
test("manual test") {
  ZIO.succeed(assertTrue(true))
} @@ ignore

// Run before others
test("setup") {
  ZIO.succeed(assertTrue(true))
} @@ before(Console.printLine("Starting tests"))

// Platform-specific
test("linux only") {
  ZIO.succeed(assertTrue(true))
} @@ ifProp("os.name")(_.toLowerCase.contains("linux"))
```

## Contract Testing

### BlobStore Contract

```scala
import graviton.runtime.stores.{BlobStore, InMemoryBlobStore}
import zio.*
import zio.stream.*
import zio.test.*

trait BlobStoreContract:
  def makeStore: UIO[BlobStore]

  def spec: Spec[Any, Throwable] =
    suite("BlobStore Contract")(
      test("put and get round-trip") {
        val payload = "test".getBytes("UTF-8")
        for
          store <- makeStore
          write <- ZStream.fromIterable(payload).run(store.put())
          out   <- store.get(write.key).runCollect
        yield assertTrue(out.toArray.sameElements(payload))
      },
    )

object InMemoryBlobStoreContractSpec extends BlobStoreContract:
  def makeStore = InMemoryBlobStore.make().widen
```

## Coverage

### Generate Reports

```bash
# Run tests with coverage
./sbt clean coverage test

# Generate report
./sbt coverageReport

# View report
open target/scala-3.3.3/scoverage-report/index.html
```

### Coverage Goals

- **Core modules**: 80%+ coverage
- **Runtime**: 70%+ coverage
- **Backends**: 60%+ (integration tests)
- **Protocols**: 50%+ (e2e tests)

## CI/CD Integration

### GitHub Actions

```yaml
name: Tests

on: [push, pull_request]

jobs:
  test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      
      - uses: actions/setup-java@v3
        with:
          distribution: 'temurin'
          java-version: '21'
          cache: 'sbt'
      
      - name: Run tests
        run: ./sbt clean coverage test coverageReport
        env:
          TESTCONTAINERS: '1'
      
      - name: Upload coverage
        uses: codecov/codecov-action@v3
```

## Best Practices

### ✅ Do

- Write tests first (TDD)
- Test edge cases
- Use property-based testing for algorithms
- Keep tests isolated and independent
- Use descriptive test names
- Test error cases

### ❌ Don't

- Test implementation details
- Share mutable state between tests
- Use `Thread.sleep` (use `TestClock` instead)
- Ignore flaky tests
- Write slow tests without `@@ timeout`
- Forget to test error paths

## Debugging Tests

### Enable Logging

```scala
test("debug test") {
  ZIO.succeed(assertTrue(true))
} @@ TestAspect.debug  // Enables debug logging
```

### Interactive Debugging

```bash
# Run specific test with debugger
./sbt -jvm-debug 5005
> testOnly graviton.core.BinaryKeySpec
```

### Test Output

```scala
test("with output") {
  for {
    _ <- Console.printLine("Starting test")
    result <- operation()
    _ <- Console.printLine(s"Result: $result")
  } yield assertTrue(result.isSuccess)
}
```

## See Also

- **[Contributing](./contributing.md)** — Development workflow
- **[Performance](../ops/performance.md)** — Performance testing
- **[Architecture](../architecture.md)** — System design

::: tip
Run tests frequently during development. Fast feedback is key!
:::
