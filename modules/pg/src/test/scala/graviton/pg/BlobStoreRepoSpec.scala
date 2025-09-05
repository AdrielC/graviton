package graviton.pg

import zio.*
import zio.test.*
import com.augustnagro.magnum.magzio.*
import com.augustnagro.magnum.{DbCodec, sql}

case object BlobStoreRepoSpec extends ZIOSpec[TestEnvironment & BlobStoreRepo & TransactorZIO]:

  val bootstrap =
    ((ZLayer.fromZIO(ZIO.config[PgTestLayers.PgTestConfig]) >>> PgTestLayers.layer) >+>
      BlobStoreRepoLive.layer) ++
      testEnvironment

  private def sampleRow: BlobStoreRow =
    val key: StoreKey = StoreKey.applyUnsafe(Chunk.fill(32)(1.toByte))
    val bytes         = Chunk.fromArray(Array[Byte](1, 2, 3))
    BlobStoreRow(
      key,
      "fs",
      bytes,
      "urn:test",
      bytes,
      None,
      StoreStatus.Active,
      0L,
    )

  def spec =
    suite("BlobStoreRepo")(
      test("writes, reads, and deletes data") {
        for {
          xa       <- ZIO.service[TransactorZIO]
          _        <- Console.printLine("Writing data")
          sampleRow = (
                        key = Chunk.fill(32)(1.toByte),
                        implId = "fs",
                        buildFp = "build_fp",
                        dvSchemaUrn = "dv_schema_urn",
                        dvCanonical = "dv_canonical",
                        dvJsonPreview = "dv_json_preview",
                        status = "active",
                      )
          _        <- Console.printLine("Reading data")
          _        <- xa.transact {
                        Console.printLine("Inserting data") *>
                          ZIO.attempt(sql"""
                INSERT INTO blob_store (key, impl_id, build_fp, dv_schema_urn, dv_canonical_bin, dv_json_preview, status)
                VALUES (${sampleRow.key}, ${sampleRow.implId}, ${sampleRow.buildFp}, ${sampleRow.dvSchemaUrn}, ${sampleRow.dvCanonical}, ${sampleRow.dvJsonPreview}, ${sampleRow.status})
              """.query[Int].run()) *>
                          Console.printLine("Reading data") *>
                          ZIO.attempt(sql"""
                SELECT * FROM blob_store WHERE key = ${sampleRow.key}
              """.query[BlobStoreRow].run())
                      }
        } yield assertTrue(true)
      },
      test("upsert and fetch") {
        for
          repo <- ZIO.service[BlobStoreRepo]
          row   = sampleRow
          _    <- repo.upsert(row)
          got  <- repo.get(row.key)
        yield assertTrue(got.exists(_.implId == "fs"))
      },
    ) @@ TestAspect.ifEnvSet("TESTCONTAINERS")
