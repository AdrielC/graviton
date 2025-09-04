package graviton.pg

import zio.*
import zio.test.*
import zio.test.Assertion.*
import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.*
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.all.*

object BlobStoreRepoSpec extends ZIOSpecDefault:

  private val repoLayer =
    PgTestLayers.transactorLayer >>> BlobStoreRepoLive.layer

  private def sampleRow: BlobStoreRow =
    val key: StoreKey = Chunk.fill(32)(1.toByte).asInstanceOf[StoreKey]
    val bytes = Chunk.fromArray(Array[Byte](1, 2, 3))
    BlobStoreRow(
      key,
      "fs",
      bytes,
      "urn:test",
      bytes,
      None,
      StoreStatus.Active,
      0L
    )

  def spec =
    suite("BlobStoreRepo")(
      test("upsert and fetch") {
        for
          repo <- ZIO.service[BlobStoreRepo]
          row = sampleRow
          _ <- repo.upsert(row)
          got <- repo.get(row.key)
        yield assertTrue(got.exists(_.implId == "fs"))
      }
    ).provideLayerShared(repoLayer) @@ TestAspect.ifEnvSet("TESTCONTAINERS")
