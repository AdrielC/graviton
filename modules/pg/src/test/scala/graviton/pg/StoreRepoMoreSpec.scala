package graviton
package pg

import graviton.db.*

import com.augustnagro.magnum.magzio.TransactorZIO
import com.augustnagro.magnum.sql
import zio.*
import zio.json.ast.Json
import zio.test.*

object StoreRepoMoreSpec extends PgTestSpec[containers.TestContainer] {

  private def sampleRow(seed: Int, status: StoreStatus): StoreRow =
    StoreRow(
      key = StoreKey.applyUnsafe(Chunk.fill(32)((seed + 7).toByte)),
      implId = s"impl-$seed",
      buildFp = Chunk.fromArray(Array.fill(8)((seed * 11).toByte)),
      dvSchemaUrn = s"urn:test:$seed",
      dvCanonical = Chunk.fromArray(Array.fill(16)((seed * 13).toByte)),
      dvJsonPreview = Some(Json.Obj("seed" -> Json.Num(seed))),
      status = status,
      version = 0L,
    )

  override def spec: Spec[Environment & Scope, Any] =
    suite("StoreRepo - more")(
      test("updated_at and version bump on update") {
        for {
          xa   <- ZIO.service[TransactorZIO]
          repo <- ZIO.service[StoreRepo]
          row   = sampleRow(100, StoreStatus.Active)
          _    <- repo.upsert(row)
          t1   <- xa.transact(sql"SELECT updated_at, version FROM store WHERE key = ${row.key}".query[(java.time.OffsetDateTime, Long)].run())
          _    <- TestClock.adjust(1.second) // ensure time moves
          _    <- repo.upsert(row.copy(status = StoreStatus.Paused))
          t2   <- xa.transact(sql"SELECT updated_at, version FROM store WHERE key = ${row.key}".query[(java.time.OffsetDateTime, Long)].run())
        } yield assertTrue(
          t1.nonEmpty,
          t2.nonEmpty,
          t2.head._2 == t1.head._2 + 1,
          t2.head._1.isAfter(t1.head._1) || t2.head._1.isEqual(t1.head._1),
        )
      },
      test("uniqueness on (impl_id, build_fp, dv_hash) is enforced") {
        for {
          xa   <- ZIO.service[TransactorZIO]
          repo <- ZIO.service[StoreRepo]
          row1  = sampleRow(200, StoreStatus.Active)
          // Duplicate triple but different key should violate unique index
          row2  = row1.copy(key = StoreKey.applyUnsafe(Chunk.fill(32)(9.toByte)))
          _    <- repo.upsert(row1)
          dup  <- repo.upsert(row2).either
        } yield assertTrue(dup.isLeft)
      },
      test("SQL update sets status to paused") {
        for {
          xa   <- ZIO.service[TransactorZIO]
          repo <- ZIO.service[StoreRepo]
          row   = sampleRow(300, StoreStatus.Active)
          _    <- repo.upsert(row)
          _    <- xa.transact(sql"UPDATE store SET status = 'paused'::store_status_t WHERE key = ${row.key}".update.run())
          st   <- xa.transact(sql"SELECT status FROM store WHERE key = ${row.key}".query[StoreStatus].run())
        } yield assertTrue(st.headOption.contains(StoreStatus.Paused))
      },
    ).provideShared(repoLayer) @@ onlyIfTestcontainers @@ TestAspect.sequential
}
