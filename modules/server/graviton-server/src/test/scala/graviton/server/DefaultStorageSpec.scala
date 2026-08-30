package graviton.server

import graviton.core.keys.BinaryKey
import graviton.runtime.config.{
  GravitonConfig,
  ReplicaFailureDomain,
  ReplicaRepairBatchSize,
  ReplicaTargetConfig,
  ReplicaTargetLocation,
  ReplicaTargetName,
  ReplicationConfig,
  TenantDataPlaneConfig,
}
import graviton.runtime.metrics.InMemoryMetricsRegistry
import graviton.runtime.stores.BlobStore
import graviton.runtime.tenant.TenantCellId
import graviton.integration.shardcake.ShardcakeUploadConfig
import graviton.security.SecurityConfig
import graviton.server.console.ConsoleConfig
import zio.*
import zio.stream.ZStream
import zio.test.*

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

object DefaultStorageSpec extends ZIOSpecDefault:

  override def spec: Spec[TestEnvironment, Any] =
    suite("default server storage")(
      test("uses a restart-safe filesystem CAS without external services") {
        withTempDir { root =>
          val cfg  = GravitonConfig().copy(fs = GravitonConfig.FsConfig(root = root.toString))
          val data = Chunk.fromArray("server-default-storage".getBytes(StandardCharsets.UTF_8))

          for
            written  <- ingest(cfg, data)
            restored <- retrieve(cfg, written)
          yield assertTrue(
            cfg.blobBackend == "fs",
            restored == data,
          )
        }
      },
      test("packaged filesystem topology repairs a corrupt replica automatically") {
        withTempDir { root =>
          val metadata    = root.resolve("metadata")
          val replicaA    = root.resolve("replica-a")
          val replicaB    = root.resolve("replica-b")
          val replication = ReplicationConfig(
            targets = Chunk(
              ReplicaTargetConfig(
                ReplicaTargetName.applyUnsafe("replica-a"),
                ReplicaFailureDomain.applyUnsafe("rack-a"),
                ReplicaTargetLocation.applyUnsafe(replicaA.toString),
              ),
              ReplicaTargetConfig(
                ReplicaTargetName.applyUnsafe("replica-b"),
                ReplicaFailureDomain.applyUnsafe("rack-b"),
                ReplicaTargetLocation.applyUnsafe(replicaB.toString),
              ),
            ),
            desiredReplicas = Some(2),
            writeQuorum = Some(2),
            repairInterval = 25.millis,
            repairBatchSize = ReplicaRepairBatchSize.applyUnsafe(32),
          )
          val cfg         = GravitonConfig().copy(
            fs = GravitonConfig.FsConfig(root = metadata.toString),
            replication = replication,
          )
          val data        = Chunk.fromArray("server-automatic-replica-repair".getBytes(StandardCharsets.UTF_8))

          for
            written     <- ingest(cfg, data)
            corruptPath <- onlyBlock(replicaA)
            _           <- ZIO.attemptBlocking(
                             Files.write(corruptPath, Array.fill[Byte](data.length)(0.toByte))
                           )
            restored    <- awaitRepairAndRetrieve(cfg, written, corruptPath, data)
            repaired    <- ZIO.attemptBlocking(Chunk.fromArray(Files.readAllBytes(corruptPath)))
            otherPath   <- onlyBlock(replicaB)
            other       <- ZIO.attemptBlocking(Chunk.fromArray(Files.readAllBytes(otherPath)))
          yield assertTrue(restored == data, repaired == data, other == data)
        }
      },
      test("rejects Shardcake locality with a node-local filesystem backend") {
        for exit <- Main.validateShardcakeTopology(GravitonConfig(), ShardcakeUploadConfig.Default.copy(enabled = true)).exit
        yield assertTrue(
          exit.causeOption
            .flatMap(_.failureOption)
            .exists(_.isInstanceOf[Main.ConfigurationError.ShardcakeRequiresSharedStorage])
        )
      },
      test("accepts Shardcake locality with the shared S3 and PostgreSQL composition") {
        for result <- Main
                        .validateShardcakeTopology(
                          GravitonConfig(blobBackend = "s3"),
                          ShardcakeUploadConfig.Default.copy(enabled = true),
                        )
                        .exit
        yield assertTrue(result.isSuccess)
      },
      test("keeps the unauthenticated local console fail-closed") {
        for
          rejected <- Main
                        .validateConsoleSecurity(
                          SecurityConfig.Default.copy(enabled = true),
                          ConsoleConfig.Default.copy(enabled = true),
                        )
                        .exit
          accepted <- Main
                        .validateConsoleSecurity(
                          SecurityConfig.Default.copy(enabled = false),
                          ConsoleConfig.Default.copy(enabled = true),
                        )
                        .exit
        yield assertTrue(
          rejected.causeOption
            .flatMap(_.failureOption)
            .contains(Main.ConfigurationError.ConsoleRequiresOpenLocalMode),
          accepted.isSuccess,
        )
      },
      test("accepts only full-quorum replicated storage for the multi-tenant data plane") {
        val targets        = Chunk(
          ReplicaTargetConfig(
            ReplicaTargetName.applyUnsafe("east"),
            ReplicaFailureDomain.applyUnsafe("zone-east"),
            ReplicaTargetLocation.applyUnsafe("tenant-blocks-east"),
          ),
          ReplicaTargetConfig(
            ReplicaTargetName.applyUnsafe("west"),
            ReplicaFailureDomain.applyUnsafe("zone-west"),
            ReplicaTargetLocation.applyUnsafe("tenant-blocks-west"),
          ),
        )
        val strictSecurity = SecurityConfig.Default.copy(
          enabled = true,
          oidcIssuer = Some("https://identity.example.com"),
          oidcAudience = Some("graviton"),
          oidcJwksUri = Some("https://identity.example.com/.well-known/jwks.json"),
          requireTls = true,
          auditBackend = "jdbc",
        )
        val fullQuorum     = GravitonConfig(
          blobBackend = "s3",
          replication = ReplicationConfig(targets = targets, desiredReplicas = Some(2), writeQuorum = Some(2)),
        )
        val partialQuorum  = fullQuorum.copy(
          replication = fullQuorum.replication.copy(writeQuorum = Some(1))
        )
        val erasure        = fullQuorum.copy(
          replication = fullQuorum.replication.copy(mode = graviton.runtime.config.ReplicaStorageMode.Erasure21)
        )

        for
          accepted <- Main
                        .validateTenantDataPlane(fullQuorum, TenantDataPlaneConfig(enabled = true), strictSecurity)
                        .exit
          partial  <- Main
                        .validateTenantDataPlane(partialQuorum, TenantDataPlaneConfig(enabled = true), strictSecurity)
                        .exit
          coded    <- Main
                        .validateTenantDataPlane(erasure, TenantDataPlaneConfig(enabled = true), strictSecurity)
                        .exit
        yield assertTrue(accepted.isSuccess, partial.isFailure, coded.isFailure)
      },
      test("derives one stable maintenance boundary per deployment cell") {
        val base = graviton.core.types.RepositoryNamespace.applyUnsafe("production")
        val east = TenantCellId.applyUnsafe("us-east-1")
        val west = TenantCellId.applyUnsafe("us-west-2")

        assertTrue(
          Main.tenantCellMaintenanceNamespace(base, east).value == "production:tenant-cell:us-east-1",
          Main.tenantCellMaintenanceNamespace(base, east) == Main.tenantCellMaintenanceNamespace(base, east),
          Main.tenantCellMaintenanceNamespace(base, east) != Main.tenantCellMaintenanceNamespace(base, west),
        )
      },
    ) @@ TestAspect.withLiveClock

  private def ingest(cfg: GravitonConfig, data: Chunk[Byte]): Task[BinaryKey.Blob] =
    (for
      store  <- ZIO.service[BlobStore]
      result <- ZStream.fromChunk(data).run(store.put())
    yield result.key).provide(InMemoryMetricsRegistry.layer, Main.blobLayer(cfg))

  private def retrieve(cfg: GravitonConfig, key: BinaryKey.Blob): Task[Chunk[Byte]] =
    (for
      store <- ZIO.service[BlobStore]
      bytes <- store.get(key).runCollect
    yield bytes).provide(InMemoryMetricsRegistry.layer, Main.blobLayer(cfg))

  private def awaitRepairAndRetrieve(
    cfg: GravitonConfig,
    key: BinaryKey.Blob,
    repairedPath: Path,
    expected: Chunk[Byte],
  ): Task[Chunk[Byte]] =
    (for
      store <- ZIO.service[BlobStore]
      _     <- ZIO
                 .attemptBlocking(Chunk.fromArray(Files.readAllBytes(repairedPath)) == expected)
                 .repeatUntil(identity)
                 .timeoutFail(new IllegalStateException("replica repair did not converge"))(5.seconds)
      bytes <- store.get(key).runCollect
    yield bytes).provide(InMemoryMetricsRegistry.layer, Main.blobLayer(cfg))

  private def onlyBlock(root: Path): Task[Path] =
    ZIO.attemptBlocking {
      val paths = Files.walk(root.resolve("cas/blocks"))
      try
        val files = paths.filter(Files.isRegularFile(_)).toList
        if files.size() != 1 then throw new IllegalStateException(s"expected one replica block, found ${files.size()}")
        files.get(0)
      finally paths.close()
    }

  private def withTempDir[A](f: Path => Task[A]): Task[A] =
    ZIO.acquireReleaseWith(
      ZIO.attemptBlocking(Files.createTempDirectory("graviton-server-default-"))
    )(dir =>
      ZIO.attemptBlocking {
        Files
          .walk(dir)
          .sorted(java.util.Comparator.reverseOrder())
          .forEach { path =>
            val _ = Files.deleteIfExists(path)
            ()
          }
      }.orDie
    )(f)
