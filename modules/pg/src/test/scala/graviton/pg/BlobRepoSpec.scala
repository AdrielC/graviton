package graviton.pg

import graviton.db.*

import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.TransactorZIO
import zio.*
import zio.stream.ZStream
import zio.test.{Spec as ZSpec, *}

import java.nio.file.Path

object BlobRepoSpec extends ZIOSpecDefault {

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

  private val repoLayer: ZLayer[Any, Throwable, TransactorZIO & BlockRepo & BlobRepo] =
    configLayer >>> PgTestLayers.layer >>> {
      val xa = ZLayer.environment[TransactorZIO]
      xa ++ BlockRepoLive.layer ++ BlobRepoLive.layer
    }

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

  override def spec: ZSpec[TestEnvironment & Scope, Any] =
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
    ).provideShared(repoLayer ++ testEnvironment) @@ onlyIfTestcontainers @@ TestAspect.sequential
}
