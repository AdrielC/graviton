package graviton
package pg

import graviton.db.*

import com.augustnagro.magnum.magzio.TransactorZIO
import com.augustnagro.magnum.sql
import zio.*
import zio.json.ast.Json
import zio.test.*

object BlockRepoSpec extends PgTestSpec {

  override def spec: Spec[Environment & Scope, Any] =
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
                         """
                           .query[(ReplicaStatus, Option[String], Option[String])]
                           .run()
                       }
        } yield assertTrue(rows.nonEmpty, rows.head == ((ReplicaStatus.Active, Some("etag-2"), Some("glacier"))))
      },
    ).provideShared(repoLayer) @@ onlyIfTestcontainers @@ TestAspect.sequential
}
