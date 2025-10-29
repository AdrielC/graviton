package graviton
package pg

import graviton.db.*

import com.augustnagro.magnum.magzio.*
import com.augustnagro.magnum.sql
import zio.*
import zio.stream.ZStream
import zio.test.*

object BlobRepoSpec extends ZIOSpec[ConfigProvider] {

  private val onlyIfTestcontainers = TestAspect.ifEnv("TESTCONTAINERS") { value =>
    value.trim match
      case v if v.equalsIgnoreCase("1") || v.equalsIgnoreCase("true") || v.equalsIgnoreCase("yes") => true
      case _                                                                                       => false
  }

  private def repoLayer: ZLayer[Any, Throwable, TransactorZIO & BlockRepoLive & BlobRepoLive] =
    ZLayer.make[TransactorZIO & BlockRepoLive & BlobRepoLive](
      PgTestConfig.layer,
      PgTestLayers.layer[TestContainer],
      BlockRepoLive.layer,
      BlobRepoLive.layer,
    )
  end repoLayer


  override def bootstrap: ZLayer[Any, Any, ConfigProvider] =
    ZLayer.succeed(pg.PgTestConfig.provider)
    

  private def seedAlgorithm(xa: TransactorZIO): Task[Unit] =
    xa.transact {
      sql"""
        INSERT INTO hash_algorithm (id, name, is_fips)
        VALUES (1, 'sha-256', true)
        ON CONFLICT (id) DO NOTHING
      """.update.run()
    }.unit

  private def sampleHash(seed: Int): HashBytes =
    HashBytes.applyUnsafe(Chunk.fill(32)((seed + 17).toByte))

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
