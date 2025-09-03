package graviton.pg

import zio.*
import zio.test.*
import zio.test.Assertion.*
import com.augustnagro.magnum.*
import com.augustnagro.magnum.magzio.*
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.all.*

object BlobStoreRepoSpec extends ZIOSpecDefault:

  private val containerLayer: ZLayer[Any, Throwable, PostgreSQLContainer[?]] =
    ZLayer.scoped {
      ZIO.acquireRelease {
        val c = new PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"))
        c.withInitScript("ddl.sql")
        c.withNetworkMode("host")
        c.start()
        ZIO.succeed(c: PostgreSQLContainer[?])
      }(c => ZIO.succeed(c.stop()))
    }

  private val transactorLayer
      : ZLayer[PostgreSQLContainer[?], Nothing, Transactor] =
    ZLayer.fromZIO {
      ZIO.service[PostgreSQLContainer[?]].map { c =>
        val ds = new org.postgresql.ds.PGSimpleDataSource()
        ds.setUrl(c.getJdbcUrl)
        ds.setUser(c.getUsername)
        ds.setPassword(c.getPassword)
        Transactor(ds)
      }
    }

  private val repoLayer =
    containerLayer >>> transactorLayer >>> BlobStoreRepoLive.layer

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
    ).provideLayerShared(repoLayer)
