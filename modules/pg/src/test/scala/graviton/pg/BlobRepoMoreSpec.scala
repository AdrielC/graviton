package graviton
package pg

import graviton.db.{*, given}

import com.augustnagro.magnum.magzio.TransactorZIO
import com.augustnagro.magnum.sql
import zio.*
import zio.stream.*
import zio.test.*

object BlobRepoMoreSpec extends PgTestSpec[containers.TestContainer] {

  override def spec: Spec[Environment & Scope, Any] =
    suite("BlobRepo - more")(
      test("upsertBlob returns stable id across conflicts and updates media_type_hint") {
        for {
          xa        <- ZIO.service[TransactorZIO]
          _         <- seedAlgorithm(xa)
          blockRepo <- ZIO.service[BlockRepo]
          blobRepo  <- ZIO.service[BlobRepo]
          keyHash    = hb(1)
          size       = PosLong.applyUnsafe(100L)
          _         <- blockRepo.upsertBlock(BlockKey(1.toShort, keyHash), size, None)
          bkey1      = BlobKey(1.toShort, keyHash, size, Some("application/foo"))
          id1       <- blobRepo.upsertBlob(bkey1)
          id2       <- blobRepo.upsertBlob(bkey1.copy(mediaTypeHint = Some("application/bar")))
          mt        <- xa.transact(sql"SELECT media_type_hint FROM blob WHERE id = $id1".query[Option[String]].run())
        } yield assertTrue(id1 == id2, mt.headOption.flatten.contains("application/bar"))
      },
      test("manifest exclusion constraint forbids overlapping spans and view aggregates JSON in order") {
        for {
          xa        <- ZIO.service[TransactorZIO]
          _         <- seedAlgorithm(xa)
          blockRepo <- ZIO.service[BlockRepo]
          blobRepo  <- ZIO.service[BlobRepo]
          k1         = hb(2)
          k2         = hb(3)
          size1      = PosLong.applyUnsafe(20L)
          size2      = PosLong.applyUnsafe(20L)
          _         <- blockRepo.upsertBlock(BlockKey(1.toShort, k1), size1, None)
          _         <- blockRepo.upsertBlock(BlockKey(1.toShort, k2), size2, None)
          blobId    <-
            xa.transact(
              sql"INSERT INTO blob (algo_id, hash, size_bytes, media_type_hint) VALUES (1, $k1, 40, 'application/octet-stream') RETURNING id"
                .query[java.util.UUID]
                .run()
                .head
            )
          _         <- ZStream(
                         BlockInsert(1.toShort, k1, size1, PosLong.applyUnsafe(1L)),
                         BlockInsert(1.toShort, k2, size2, PosLong.applyUnsafe(10L)), // overlaps [1,21) and [10,30)
                       ).via(blobRepo.putBlobBlocks(blobId)).runDrain.either
          // Overlap should have failed; ensure only first entry exists
          count     <- xa.transact(sql"SELECT count(*) FROM manifest_entry WHERE blob_id = $blobId".query[Long].run())
          // Verify view emits JSON array for existing entries in order
          view      <- xa.transact(sql"SELECT manifest FROM v_blob_manifest WHERE id = $blobId".query[Option[zio.json.ast.Json]].run())
        } yield assertTrue(count.headOption.contains(1L), view.headOption.flatten.nonEmpty)
      },
    ).provideShared(repoLayer) @@ onlyIfTestcontainers @@ TestAspect.sequential
}
