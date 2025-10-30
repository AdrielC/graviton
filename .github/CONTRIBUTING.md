# Contributing to Graviton

Thank you for your interest in contributing to Graviton!

## Development Setup

### Prerequisites

- **Java 21** (Temurin recommended)
- **SBT 1.11+**
- **Node.js 20+**
- **PostgreSQL** (for integration tests, optional)

### Clone and Build

```bash
git clone https://github.com/AdrielC/graviton.git
cd graviton

# Compile all modules
sbt compile

# Run tests
TESTCONTAINERS=0 ./sbt test

# Format code
./sbt scalafmtAll
```

## Project Structure

```
graviton/
├── modules/
│   ├── graviton-core/        # Pure domain types
│   ├── graviton-streams/     # ZIO streaming combinators
│   ├── graviton-runtime/     # Runtime ports and policies
│   ├── protocol/
│   │   ├── graviton-proto/   # Protobuf definitions
│   │   ├── graviton-grpc/    # gRPC services
│   │   ├── graviton-http/    # HTTP routes
│   │   └── graviton-shared/  # Cross-compiled models (JVM + JS)
│   ├── backend/
│   │   ├── graviton-s3/      # S3 backend
│   │   ├── graviton-pg/      # PostgreSQL backend
│   │   └── graviton-rocks/   # RocksDB backend
│   ├── server/
│   │   └── graviton-server/  # Application wiring
│   └── frontend/             # Scala.js interactive demo
└── docs/                     # VitePress documentation
```

## Making Changes

### Code Style

- Use **star imports** for packages: `import scala.collection.*`
- **Never throw** unless absolutely necessary - use ZIO error handling
- Format code with: `./sbt scalafmtAll`
- Check formatting with: `./sbt scalafmtCheckAll`

### Testing

```bash
# Run all tests
TESTCONTAINERS=0 ./sbt test

# Run specific module tests
./sbt "graviton-core/test"

# Run specific test
./sbt "testOnly graviton.core.HashingSpec"
```

### Frontend Development

```bash
# Build the Scala.js frontend
./sbt buildFrontend

# Watch mode for fast iteration
./sbt ~frontend/fastLinkJS

# Then in another terminal
cd docs && npm run docs:dev
```

**Note**: Generated JS files in `docs/public/js/` are **not** checked into git. They're built by CI.

### Documentation

```bash
# Build and preview documentation
cd docs
npm install
npm run docs:dev

# Visit: http://localhost:5173/graviton/
```

**Note**: Scaladoc files in `docs/public/scaladoc/` are **not** checked into git. They're built by CI.

## Pull Request Process

1. **Fork** the repository
2. **Create a branch** from `main`:
   ```bash
   git checkout -b feature/my-feature
   ```
3. **Make your changes** and commit with clear messages
4. **Format code**:
   ```bash
   ./sbt scalafmtAll
   ```
5. **Run tests**:
   ```bash
   TESTCONTAINERS=0 ./sbt test
   ```
6. **Push** to your fork
7. **Open a Pull Request** to `main`

### Commit Message Guidelines

```
<type>: <subject>

<body (optional)>
```

**Types**:
- `feat`: New feature
- `fix`: Bug fix
- `docs`: Documentation changes
- `style`: Code style changes (formatting)
- `refactor`: Code refactoring
- `test`: Adding or updating tests
- `chore`: Build, CI, or tooling changes

**Examples**:
```
feat: add WebSocket support for real-time updates

fix: resolve memory leak in blob streaming

docs: update installation guide with Docker instructions

refactor: extract common hashing logic to trait
```

## CI/CD Pipeline

All PRs run through:

1. **Format check**: `./sbt scalafmtCheckAll`
2. **Compilation**: `./sbt compile`
3. **Tests**: `./sbt test`
4. **Docs build**: `npm run docs:build`

The CI **automatically builds** the Scala.js frontend and Scaladoc.

## Schema Changes

If you modify the PostgreSQL schema:

1. Update `modules/backend/graviton-pg/src/main/resources/ddl.sql`
2. Start a local PostgreSQL instance
3. Apply the DDL:
   ```bash
   psql -d graviton -f modules/backend/graviton-pg/src/main/resources/ddl.sql
   ```
4. Regenerate bindings:
   ```bash
   PG_JDBC_URL=jdbc:postgresql://127.0.0.1:5432/graviton \
   PG_USERNAME=postgres \
   PG_PASSWORD=postgres \
   ./sbt "dbcodegen/run"
   ```
5. Commit both the DDL and generated files

## Release Process

(For maintainers)

1. Update version in `build.sbt`
2. Update `CHANGELOG.md`
3. Tag the release:
   ```bash
   git tag -a v0.1.0 -m "Release v0.1.0"
   git push origin v0.1.0
   ```
4. GitHub Actions will:
   - Run tests
   - Build artifacts
   - Publish to Maven Central
   - Deploy documentation

## Getting Help

- **Issues**: [GitHub Issues](https://github.com/AdrielC/graviton/issues)
- **Discussions**: [GitHub Discussions](https://github.com/AdrielC/graviton/discussions)
- **Documentation**: [https://adrielc.github.io/graviton/](https://adrielc.github.io/graviton/)

## Code of Conduct

Be respectful, inclusive, and constructive. We're all here to build something great together.

## License

By contributing, you agree that your contributions will be licensed under the MIT License.

---

Thank you for contributing to Graviton! 🚀
