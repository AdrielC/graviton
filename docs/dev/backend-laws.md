# Backend Laws

`graviton-backend-laws_3` is the executable contract for a logical `BlobStore`. It is a published test-support module, not an empty marker artifact.

```scala
libraryDependencies ++= Seq(
  "io.github.adrielc" %% "graviton-runtime"      % gravitonVersion,
  "io.github.adrielc" %% "graviton-backend-laws" % gravitonVersion % Test,
  "dev.zio" %% "zio-test-sbt"          % zioVersion      % Test,
)
```

Mount the laws with a scoped acquisition effect that creates an isolated empty store:

```scala
import graviton.backend.laws.BlobStoreLaws
import graviton.runtime.stores.{BlobStore, StoreError}
import zio.*
import zio.test.*

object MyBackendSpec extends ZIOSpecDefault:
  val freshStore: ZIO[Scope, StoreError, BlobStore] =
    MyBackend.scoped(testConfiguration)

  override def spec = BlobStoreLaws.suite("my backend")(freshStore)
```

The current contract proves:

- streamed write and byte-exact read preserve content identity
- duplicate writes are idempotent and produce one logical inventory entry
- bounded range reads return exactly the requested span
- opaque pages have no duplicate or omitted entries
- delete makes metadata and bytes unreachable
- interruption does not publish a partial logical blob

The Graviton build applies the published suite to both in-memory and filesystem CAS implementations. PostgreSQL, S3, and third-party adapters should mount it in their integration environment, then add backend-specific durability and fault tests. Passing the laws establishes logical behavior, not provider capacity, disaster recovery, or service-level guarantees.
