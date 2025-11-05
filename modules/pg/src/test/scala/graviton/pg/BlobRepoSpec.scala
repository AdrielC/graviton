package graviton
package pg

import graviton.db.*

import com.augustnagro.magnum.magzio.*
import com.augustnagro.magnum.sql
import zio.*
import zio.stream.ZStream
import zio.test.*

object BlobRepoSpec extends PgTestSpec[containers.TestContainer] {

  override def spec: Spec[Environment & Scope, Any] =
    suite("BlobRepo")(
      test("upsertBlob registers blob and manifest entries are persisted") {
        for {
          xa        <- ZIO.service[TransactorZIO]
          _         <- seedAlgorithm(xa)
          blockRepo <- ZIO.service[BlockRepo]
          blobRepo  <- ZIO.service[BlobRepo]
          blockKey   = BlockKey(1.toShort, sampleHash(3))
          size       = PosLong.applyUnsafe(4096L)
          _         <- blockRepo.upsertBlock(blockKey, size, inline = None)
          blobId    <- blobRepo.upsertBlob(BlobKey(blockKey.algoId, blockKey.hash, size, Some("application/octet-stream")))
          written   <- ZStream(BlockInsert(blockKey.algoId, blockKey.hash, size, PosLong.applyUnsafe(1L)))
                         .via(blobRepo.putBlobBlocks(blobId))
                         .runCollect
          found     <- blobRepo.findBlobId(BlobKey(blockKey.algoId, blockKey.hash, size, Some("application/octet-stream")))
          manifest  <- xa.transact {
                         sql"""
                            SELECT count(*) FROM manifest_entry WHERE blob_id = $blobId
                          """.query[Long].run()
                       }
        } yield assertTrue(written.nonEmpty, found.contains(blobId), manifest.headOption.contains(1L))
      }
    ).provideShared(repoLayer) @@ onlyIfTestcontainers @@ TestAspect.sequential
}
