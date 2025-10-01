package graviton.pg

import graviton.db.*

import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.TransactorZIO
import zio.*
import zio.json.ast.Json
import zio.test.{Spec as ZSpec, *}

import java.nio.file.Path

object BlockRepoSpec extends ZIOSpecDefault {

  private val onlyIfTestcontainers = TestAspect.ifEnv("TESTCONTAINERS") { value =>
    value.trim match
      case v if v.equalsIgnoreCase("1") || v.equalsIgnoreCase("true") || v.equalsIgnoreCase("yes") => true
      case _                                                                                       => false
  }

  private val configLayer: ZLayer[Any, Nothing, PgTestLayers.PgTestConfig] =
    ZLayer.succeed(
      PgTestLayers.PgTestConfig(
        image = "postgres",
        tag = "17-alpine",
        registry = None,
        repository = None,
        username = "postgres",
        password = "postgres",
        database = "postgres",
        initScript = Some(Path.of("ddl.sql")),
        startupAttempts = 3,
        startupTimeout = 90L,
      )
    )

  private val repoLayer: ZLayer[Any, Throwable, TransactorZIO & StoreRepo & BlockRepo] =
    configLayer >>> PgTestLayers.layer >>> {
      val xa = ZLayer.environment[TransactorZIO]
      xa ++ StoreRepoLive.layer ++ BlockRepoLive.layer
    }

  private def seedAlgorithm(xa: TransactorZIO): Task[Unit] =
    xa.transact {
      sql"""
        INSERT INTO hash_algorithm (id, name, is_fips)
        VALUES (1, 'sha-256', true)
        ON CONFLICT (id) DO NOTHING
      """.update.run()
    }.unit

  private def storeRow(seed: Int, status: StoreStatus): StoreRow =
    StoreRow(
      key = StoreKey.applyUnsafe(Chunk.fill(32)((seed + 1).toByte)),
      implId = s"impl-$seed",
      buildFp = Chunk.fromArray(Array.fill(8)((seed * 2).toByte)),
      dvSchemaUrn = s"urn:test:$seed",
      dvCanonical = Chunk.fromArray(Array.fill(8)((seed * 3).toByte)),
      dvJsonPreview = Some(Json.Obj("seed" -> Json.Num(seed))),
      status = status,
      version = 0L,
    )

  private def sampleHash(seed: Int): HashBytes =
    HashBytes.applyUnsafe(Chunk.fill(32)((seed + 42).toByte))

  override def spec: ZSpec[TestEnvironment & Scope, Any] =
    suite("BlockRepo")(
      test("upsertBlock stores block metadata and head lookup succeeds") {
        for {
          xa        <- ZIO.service[TransactorZIO]
          _         <- seedAlgorithm(xa)
          blockRepo <- ZIO.service[BlockRepo]
          key        = BlockKey(1.toShort, sampleHash(1))
          size       = PosLong.applyUnsafe(1024L)
          inserted  <- blockRepo.upsertBlock(key, size, inline = None)
          head      <- blockRepo.getHead(key)
        } yield assertTrue(inserted == 1, head.contains((size, false)))
      },
      test("linkReplica upserts replica rows with latest metadata") {
        for {
          xa        <- ZIO.service[TransactorZIO]
          _         <- seedAlgorithm(xa)
          blockRepo <- ZIO.service[BlockRepo]
          storeRepo <- ZIO.service[StoreRepo]
          store      = storeRow(5, StoreStatus.Active)
          _         <- storeRepo.upsert(store)
          key        = BlockKey(1.toShort, sampleHash(2))
          size       = PosLong.applyUnsafe(2048L)
          _         <- blockRepo.upsertBlock(key, size, inline = None)
          _         <- blockRepo.linkReplica(key, store.key, Some("a"), size, Some("etag-1"), Some("standard"))
          _         <- blockRepo.linkReplica(key, store.key, Some("a"), size, Some("etag-2"), Some("glacier"))
          rows      <- xa.transact {
                         sql"""
                           SELECT status, etag, storage_class
                           FROM replica
                           WHERE store_key = ${store.key}
                         """.query[(ReplicaStatus, Option[String], Option[String])].run()
                       }
        } yield assertTrue(rows.nonEmpty, rows.head == ((ReplicaStatus.Active, Some("etag-2"), Some("glacier"))))
      },
    ).provideShared(repoLayer ++ testEnvironment) @@ onlyIfTestcontainers @@ TestAspect.sequential
}
