# Contributing Guide

Thank you for your interest in contributing to Graviton!

## Code of Conduct

Be respectful, collaborative, and professional. We're building something great together.

## Development Setup

### Prerequisites

- **JDK 21+**: OpenJDK or Oracle JDK
- **sbt 1.11+**: Scala build tool
- **Git**: Version control
- **PostgreSQL 18+** (optional): For integration tests
- **Node.js 20+**: For documentation and the Scala.js demo bundle

### Clone and Build

```bash
# Clone with submodules
git clone --recursive https://github.com/AdrielC/graviton.git
cd graviton

# Or if already cloned
git submodule update --init --recursive

# Compile everything
sbt compile

# Format & run the default JVM + JS test matrix (keeping TestContainers off by default)
TESTCONTAINERS=0 ./sbt scalafmtAll test

# (Optional) Exercise TestContainers-backed suites
TESTCONTAINERS=1 ./sbt test

# (Optional) Rebuild the documentation demo assets after frontend changes
./sbt buildFrontend
```

## Coding Standards

### Style Guide

Follow the existing code style:

```scala
// ✅ Good: Use star imports
import zio.*
import zio.stream.*

// ❌ Avoid: Selective imports (unless intentional)
import zio.{ZIO, UIO, Task}

// ✅ Good: Clear, descriptive names
def uploadBlobWithRetry(key: BinaryKey, data: Chunk[Byte]): ZIO[BlobStore, UploadError, Unit]

// ❌ Avoid: Abbreviated names
def upld(k: BinaryKey, d: Chunk[Byte]): ZIO[BlobStore, UploadError, Unit]

// ✅ Good: Leverage type inference
val result = for {
  data <- readFile(path)
  key <- hashData(data)
  _ <- store.put(key, data)
} yield key

// ❌ Avoid: Unnecessary type annotations
val result: ZIO[BlobStore, Throwable, BinaryKey] = for {
  data: Chunk[Byte] <- readFile(path)
  key: BinaryKey <- hashData(data)
  _: Unit <- store.put(key, data)
} yield key
```

### Error Handling

```scala
// ✅ Good: Never throw unless absolutely necessary
def safeOperation: IO[DomainError, Result] =
  ZIO.attempt(riskyOperation()).refineToOrDie[DomainError]

// ❌ Avoid: Throwing exceptions
def unsafeOperation: Result =
  if (condition) result
  else throw new RuntimeException("Failed!")

// ✅ Good: Use typed errors
sealed trait UploadError
object UploadError {
  case class BlobTooLarge(size: Long, max: Long) extends UploadError
  case class InvalidKey(key: String) extends UploadError
  case class StorageError(cause: Throwable) extends UploadError
}

// ✅ Good: Provide helpful context
ZIO.fail(UploadError.BlobTooLarge(actualSize, maxSize))
```

### Documentation

```scala
/**
 * Stores a blob with the given key.
 *
 * The blob is written to the underlying storage backend and replicated
 * according to the configured replication policy.
 *
 * @param key The content-addressable key
 * @param data The blob data
 * @return Unit on success, or a StorageError
 *
 * @example {{{
 * val key = BinaryKey.fromHash(HashAlgo.SHA256, data)
 * blobStore.put(key, data)
 * }}}
 */
def put(key: BinaryKey, data: Chunk[Byte]): IO[StorageError, Unit]
```

## Testing

### Unit Tests

```scala
import zio.test.*

object BinaryKeySpec extends ZIOSpecDefault {
  def spec = suite("BinaryKey")(
    test("fromHash creates valid key") {
      for {
        data <- ZIO.succeed("test".getBytes)
        hash <- HashAlgo.SHA256.hash(data)
        key = BinaryKey.fromHash(HashAlgo.SHA256, hash)
      } yield assertTrue(key.algo == HashAlgo.SHA256)
    },
    
    test("hex round-trip") {
      check(Gen.chunkOfBounded(32, 32)(Gen.byte)) { bytes =>
        val key = BinaryKey.fromBytes(bytes)
        val hex = key.hex
        val decoded = BinaryKey.fromHex(hex)
        assertTrue(decoded == Some(key))
      }
    }
  )
}
```

### Integration Tests

```scala
object BlobStoreIntegrationSpec extends ZIOSpecDefault {
  def spec = suite("BlobStore Integration")(
    test("put and get round-trip") {
      for {
        store <- ZIO.service[BlobStore]
        key = BinaryKey.random
        data = Chunk.fromArray("test data".getBytes)
        _ <- store.put(key, data)
        retrieved <- store.get(key)
      } yield assertTrue(retrieved == data)
    }
  ).provide(
    InMemoryBlobStore.layer,
    // Or for real backend:
    // PostgresBlobStore.layer,
    // PostgresConfig.layer
  ) @@ TestAspect.withLiveEnvironment
}
```

### Property-Based Testing

```scala
test("RangeSet union is commutative") {
  check(rangeSetGen, rangeSetGen) { (rs1, rs2) =>
    val union1 = rs1.union(rs2)
    val union2 = rs2.union(rs1)
    assertTrue(union1 == union2)
  }
}
```

## Pull Request Process

### 1. Create a Branch

```bash
# Feature branch
git checkout -b feature/add-tiered-storage

# Bug fix branch
git checkout -b fix/range-set-normalization

# Documentation branch
git checkout -b docs/improve-api-examples
```

### 2. Make Changes

- Write code following the style guide
- Add tests for new functionality
- Update documentation
- Run formatter: `sbt scalafmtAll`

### 3. Commit

```bash
# Stage changes
git add .

# Commit with clear message
git commit -m "Add tiered storage backend

- Implement hot/warm/cold tier selection
- Add tier migration background job
- Update documentation with tiering examples
"
```

**Commit Message Format:**

```
<type>: <subject>

<body>

<footer>
```

**Types:**
- `feat`: New feature
- `fix`: Bug fix
- `docs`: Documentation
- `style`: Formatting
- `refactor`: Code restructuring
- `test`: Adding tests
- `chore`: Build/tooling

### 4. Push and Create PR

```bash
# Push to your fork
git push origin feature/add-tiered-storage

# Create PR on GitHub
# - Fill out the PR template
# - Link any related issues
# - Add screenshots/examples if applicable
```

### 5. Code Review

- Address reviewer feedback
- Keep PR focused and reasonably sized
- Squash commits if requested

### 6. Merge

Once approved:
- Squash and merge for clean history
- Delete branch after merge

## Project Structure

```
graviton/
├── modules/
│   ├── graviton-core/          # Pure domain types
│   │   └── src/main/scala/
│   ├── graviton-streams/       # ZIO Stream utilities
│   ├── graviton-runtime/       # Service ports
│   ├── protocol/
│   │   ├── graviton-proto/     # Protobuf definitions
│   │   ├── graviton-grpc/      # gRPC services
│   │   └── graviton-http/      # HTTP routes
│   ├── backend/
│   │   ├── graviton-s3/        # S3 backend
│   │   ├── graviton-pg/        # PostgreSQL backend
│   │   └── graviton-rocks/     # RocksDB backend
│   └── server/
│       └── graviton-server/    # Application wiring
├── docs/                        # VitePress documentation
├── scripts/                     # Build scripts
└── build.sbt                    # Build configuration
```

## Adding New Features

### 1. Design Document

For significant features, create a design doc:

```markdown
# Feature: Tiered Storage

## Motivation
Support hot/warm/cold storage tiers for cost optimization.

## Design
- Define tier policy interface
- Implement tier selection strategy
- Add background migration job

## API
\`\`\`scala
trait TierPolicy {
  def selectTier(metadata: BlobMetadata): Tier
}
\`\`\`

## Testing
- Unit tests for tier selection logic
- Integration test with multiple backends
- Performance benchmark

## Migration
N/A - additive change
```

### 2. Implementation

- Start with interfaces in `graviton-runtime`
- Add tests alongside code
- Document public APIs

### 3. Documentation

- Update relevant guides
- Add API examples
- Update CHANGELOG

## Schema Changes

If you modify the PostgreSQL schema:

```bash
# 1. Update DDL
vim modules/backend/graviton-pg/src/main/resources/ddl.sql

# 2. Apply to local database
psql -U postgres -d graviton -f modules/backend/graviton-pg/src/main/resources/ddl.sql

# 3. Regenerate bindings
PG_JDBC_URL=jdbc:postgresql://localhost:5432/graviton \
PG_USERNAME=postgres \
PG_PASSWORD=postgres \
./sbt "dbcodegen/run"

# 4. Commit both DDL and generated code
git add modules/backend/graviton-pg/
git commit -m "schema: add tiering metadata columns"
```

## Documentation

### Building Docs

```bash
cd docs
npm install
npm run docs:build

# Or preview locally
npm run docs:dev
```

### Adding Pages

1. Create markdown file: `docs/guide/new-feature.md`
2. Update navigation in `docs/.vitepress/config.ts`
3. Add links from related pages

## Release Process

(For maintainers)

1. Update version in `build.sbt`
2. Update `CHANGELOG.md`
3. Create git tag: `git tag v0.2.0`
4. Push tag: `git push origin v0.2.0`
5. GitHub Actions will publish artifacts

## Getting Help

- **Questions**: Open a GitHub Discussion
- **Bugs**: Open a GitHub Issue
- **Security**: Email security@graviton.io (if applicable)

## License

By contributing, you agree that your contributions will be licensed under the same license as the project.

---

**Thank you for contributing to Graviton!**
