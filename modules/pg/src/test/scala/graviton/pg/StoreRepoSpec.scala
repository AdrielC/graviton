package graviton.pg

import graviton.db.{StoreRepo, StoreRow, StoreStatus, StoreKey}

import zio.*
import zio.json.ast.Json
import zio.test.{Spec as ZSpec} 
import java.nio.file.Path

object StoreRepoSpec extends ZIOSpecDefault {

  private val onlyIfTestcontainers = TestAspect.ifEnv("TESTCONTAINERS") { value =>
    value.trim match
      case v if v.equalsIgnoreCase("1") || v.equalsIgnoreCase("true") || v.equalsIgnoreCase("yes") => true
      case _                                                                                       => false
  }

  private val pgConfigLayer: ZLayer[Any, Nothing, PgTestLayers.PgTestConfig] =
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

  private val storeRepoLayer: ZLayer[Any, Throwable, StoreRepo] =
    pgConfigLayer >>> PgTestLayers.layer >>> StoreRepoLive.layer

  private def sampleRow(
    status: StoreStatus,
    seed: Int,
  ): StoreRow = {
    val keyBytes = Chunk.fill(32)((seed + 1).toByte)
    StoreRow(
      key = StoreKey.applyUnsafe(keyBytes),
      implId = s"impl-$seed",
      buildFp = Chunk.fromArray(Array.fill(8)((seed * 2).toByte)),
      dvSchemaUrn = s"urn:test:$seed",
      dvCanonical = Chunk.fromArray(Array.fill(8)((seed * 3).toByte)),
      dvJsonPreview = Some(Json.Obj("seed" -> Json.Num(seed))),
      status = status,
      version = 0L,
    )
  }
  
  override def spec: ZSpec[TestEnvironment & Scope, Any] =
    suite("StoreRepo")(
      test("upsert inserts and updates existing records") {
        for {
          repo   <- ZIO.service[StoreRepo]
          row     = sampleRow(StoreStatus.Active, seed = 1)
          _      <- repo.upsert(row)
          first  <- repo.get(row.key)
          _      <- repo.upsert(row.copy(status = StoreStatus.Paused))
          second <- repo.get(row.key)
        } yield assertTrue(
          first.exists(_.status == StoreStatus.Active),
          second.exists(_.status == StoreStatus.Paused),
          second.exists(_.version == first.fold(0L)(_.version + 1)),
        )
      },
      test("listActive returns only active stores respecting cursor page size") {
        for {
          repo  <- ZIO.service[StoreRepo]
          _     <- repo.upsert(sampleRow(StoreStatus.Active, 1))
          _     <- repo.upsert(sampleRow(StoreStatus.Active, 2))
          _     <- repo.upsert(sampleRow(StoreStatus.Active, 3))
          _     <- repo.upsert(sampleRow(StoreStatus.Paused, 99))
          cursor = graviton.db.Cursor.initial.copy(pageSize = 2L)
          rows  <- repo.listActive(Some(cursor)).take(3).runCollect
        } yield assertTrue(rows.length == 3, rows.forall(_.status == StoreStatus.Active))
      },
    ).provideShared(storeRepoLayer ++ testEnvironment) @@ onlyIfTestcontainers @@ TestAspect.sequential
}