package graviton
package pg

import graviton.db.*

import com.augustnagro.magnum.magzio.TransactorZIO
import com.augustnagro.magnum.sql
import zio.*
import zio.test.*

import pg.TestContainer

object BlockRepoMoreSpec extends PgTestSpec[TestContainer] {

  override def spec: Spec[Environment & Scope, Any] =
    suite("BlockRepo - more")(
      test("inline_bytes size must equal size_bytes when present") {
        for {
          xa    <- ZIO.service[TransactorZIO]
          _     <- seedAlgorithm(xa)
          repo  <- ZIO.service[BlockRepo]
          key    = BlockKey(1.toShort, sampleHash(1))
          size   = PosLong.applyUnsafe(32L)
          inline = SmallBytes.applyUnsafe(Chunk.fromArray(Array.fill(8)(1.toByte)))
          res   <- repo.upsertBlock(key, size, inline = Some(inline)).either
        } yield assertTrue(res.isLeft)
      },
      test("replica last_verified_at is set on update and cascades on block delete") {
        for {
          xa        <- ZIO.service[TransactorZIO]
          _         <- seedAlgorithm(xa)
          blockRepo <- ZIO.service[BlockRepo]
          storeRepo <- ZIO.service[StoreRepo]
          store      = storeRow(2, StoreStatus.Active)
          _         <- storeRepo.upsert(store)
          key        = BlockKey(1.toShort, sampleHash(2))
          size       = PosLong.applyUnsafe(64L)
          _         <- blockRepo.upsertBlock(key, size, inline = None)
          _         <- blockRepo.linkReplica(key, store.key, Some("sec"), size, Some("e1"), Some("standard"))
          first     <-
            xa.transact(
              sql"SELECT last_verified_at FROM replica WHERE algo_id = ${key.algoId} AND hash = ${key.hash} AND store_key = ${store.key}"
                .query[Option[java.time.OffsetDateTime]]
                .run()
            )
          _         <- blockRepo.linkReplica(key, store.key, Some("sec"), size, Some("e2"), Some("glacier"))
          second    <-
            xa.transact(
              sql"SELECT last_verified_at FROM replica WHERE algo_id = ${key.algoId} AND hash = ${key.hash} AND store_key = ${store.key}"
                .query[Option[java.time.OffsetDateTime]]
                .run()
            )
          _         <- xa.transact(sql"DELETE FROM block WHERE algo_id = ${key.algoId} AND hash = ${key.hash}".update.run())
          remains   <- xa.transact(sql"SELECT count(*) FROM replica WHERE store_key = ${store.key}".query[Long].run())
        } yield assertTrue(first.headOption.flatten.isEmpty, second.headOption.flatten.nonEmpty, remains.headOption.contains(0L))
      },
    ).provideShared(repoLayer) @@ onlyIfTestcontainers @@ TestAspect.sequential
}
