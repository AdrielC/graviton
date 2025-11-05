package graviton.pg

import zio.*
import zio.test.*
import graviton.db.*
import com.augustnagro.magnum.magzio.TransactorZIO
import com.augustnagro.magnum.sql
import zio.json.ast.Json
import org.testcontainers.containers.PostgreSQLContainer

trait PgTestSpec[C <: PostgreSQLContainer[C]: {PgTestLayers, Tag}] extends ZIOSpec[ConfigProvider] {

  def bootstrap: ZLayer[Any, Any, ConfigProvider] =
    ZLayer.succeed(PgTestConfig.provider)

  protected val onlyIfTestcontainers = TestAspect.ifEnv("TESTCONTAINERS") { value =>
    value.trim match
      case v if v.equalsIgnoreCase("1") || v.equalsIgnoreCase("true") || v.equalsIgnoreCase("yes") => true
      case _                                                                                       => false
  }

  protected def repoLayer: ZLayer[Any, Throwable, TransactorZIO & StoreRepo & BlockRepo & BlobRepo] =
    ZLayer.make[TransactorZIO & StoreRepo & BlockRepo & BlobRepo](
      PgTestConfig.layer,
      PgTestLayers.layer[C],
      StoreRepoLive.layer,
      BlockRepoLive.layer,
      BlobRepoLive.layer,
    )

  protected def seedAlgorithm(xa: TransactorZIO): Task[Unit] =
    xa.transact {
      sql"""
        INSERT INTO hash_algorithm (id, name, is_fips)
        VALUES (1, 'sha-256', true)
        ON CONFLICT (id) DO NOTHING
      """.update.run()
    }.unit

  protected def storeRow(seed: Int, status: StoreStatus): StoreRow =
    StoreRow(
      key = StoreKey.applyUnsafe(Chunk.fill(32)((seed + 21).toByte)),
      implId = s"impl-$seed",
      buildFp = Chunk.fromArray(Array.fill(8)((seed * 2).toByte)),
      dvSchemaUrn = s"urn:test:$seed",
      dvCanonical = Chunk.fromArray(Array.fill(8)((seed * 3).toByte)),
      dvJsonPreview = Some(Json.Obj("seed" -> Json.Num(seed))),
      status = status,
      version = 0L,
    )

  protected def sampleHash(seed: Int): HashBytes =
    HashBytes.applyUnsafe(Chunk.fill(32)((seed + 42).toByte))

  def hb(seed: Int): HashBytes =
    HashBytes.applyUnsafe(Chunk.fill(32)((seed + 42).toByte))
}
