package graviton
package pg

import graviton.db.*

import com.augustnagro.magnum.magzio.TransactorZIO
import com.augustnagro.magnum.sql
import zio.*
import zio.test.*

object PgViewsSpec extends PgTestSpec[containers.TestContainer] {

  override def spec: Spec[Environment & Scope, Any] =
    suite("PG Views")(
      test("v_store_inventory aggregates per store") {
        for {
          xa        <- ZIO.service[TransactorZIO]
          _         <- seedAlgorithm(xa)
          storeRepo <- ZIO.service[StoreRepo]
          blockRepo <- ZIO.service[BlockRepo]
          storeKey   = StoreKey.applyUnsafe(Chunk.fill(32)(1.toByte))
          _         <- storeRepo.upsert(
                         StoreRow(
                           storeKey,
                           "impl-x",
                           Chunk.fromArray(Array.fill(8)(1.toByte)),
                           "urn:x",
                           Chunk.fromArray(Array.fill(8)(2.toByte)),
                           None,
                           StoreStatus.Active,
                           0L,
                         )
                       )
          key        = BlockKey(1.toShort, HashBytes.applyUnsafe(Chunk.fill(32)(2.toByte)))
          _         <- blockRepo.upsertBlock(key, PosLong.applyUnsafe(10L), None)
          _         <- blockRepo.linkReplica(key, storeKey, Some("s"), PosLong.applyUnsafe(10L), None, None)
          row       <- xa.transact(
                         sql"SELECT total_replicas, active_replicas FROM v_store_inventory WHERE key = $storeKey"
                           .query[(Option[Long], Option[Long])]
                           .run()
                       )
        } yield assertTrue(row.headOption.exists { case (t, a) => t.contains(1L) && a.contains(1L) })
      },
      test("v_block_replica_health summarizes per block") {
        for {
          xa        <- ZIO.service[TransactorZIO]
          _         <- seedAlgorithm(xa)
          storeRepo <- ZIO.service[StoreRepo]
          blockRepo <- ZIO.service[BlockRepo]
          storeKey   = StoreKey.applyUnsafe(Chunk.fill(32)(3.toByte))
          _         <- storeRepo.upsert(
                         StoreRow(
                           storeKey,
                           "impl-y",
                           Chunk.fromArray(Array.fill(8)(3.toByte)),
                           "urn:y",
                           Chunk.fromArray(Array.fill(8)(4.toByte)),
                           None,
                           StoreStatus.Active,
                           0L,
                         )
                       )
          key        = BlockKey(1.toShort, HashBytes.applyUnsafe(Chunk.fill(32)(4.toByte)))
          _         <- blockRepo.upsertBlock(key, PosLong.applyUnsafe(20L), None)
          _         <- blockRepo.linkReplica(key, storeKey, None, PosLong.applyUnsafe(20L), None, None)
          row       <-
            xa.transact(
              sql"SELECT replica_count, active_count, has_active FROM v_block_replica_health WHERE algo_id = ${key.algoId} AND hash = ${key.hash}"
                .query[(Option[Long], Option[Long], Option[Boolean])]
                .run()
            )
        } yield assertTrue(row.headOption.exists { case (rc, ac, ha) => rc.contains(1L) && ac.contains(1L) && ha.contains(true) })
      },
    ).provideShared(repoLayer) @@ onlyIfTestcontainers @@ TestAspect.sequential
}
