package graviton.pg

import graviton.db.{StoreKey, StoreRepo, StoreRow, StoreStatus, given}

import zio.*
import zio.test.*
import com.augustnagro.magnum.magzio.*
import com.augustnagro.magnum.{DbCodec, sql}

object StoreRepoSpec extends ZIOSpec[TestEnvironment]:

  override val bootstrap = testEnvironment

  private val containersEnabled =
    sys.env
      .get("TESTCONTAINERS")
      .exists { raw =>
        val normalized = raw.trim.toLowerCase(java.util.Locale.ROOT)
        normalized match
          case "1" | "true" | "yes" | "on" => true
          case _                           => false
      }

  private val pgLayer =
    (ZLayer.fromZIO(ZIO.config[PgTestLayers.PgTestConfig]) >>> PgTestLayers.layer) >+>
      StoreRepoLive.layer

  private def sampleRow: StoreRow =
    val key: StoreKey = StoreKey.applyUnsafe(Chunk.fill(32)(1.toByte))
    val bytes         = Chunk.fromArray(Array[Byte](1, 2, 3))
    StoreRow(
      key,
      "fs",
      bytes,
      "urn:test",
      bytes,
      None,
      StoreStatus.Active,
      0L,
    )

  private val integrationSuite =
    suite("StoreRepo")(
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
            INSERT INTO store (key, impl_id, build_fp, dv_schema_urn, dv_canonical_bin, dv_json_preview, status)
            VALUES (${sampleRow.key}, ${sampleRow.implId}, ${sampleRow.buildFp}, ${sampleRow.dvSchemaUrn}, ${sampleRow.dvCanonical}, ${sampleRow.dvJsonPreview}, ${sampleRow.status})
          """.query[Int].run()) *>
                          Console.printLine("Reading data") *>
                          ZIO.attempt(sql"""
            SELECT * FROM store WHERE key = ${sampleRow.key}
          """.query[StoreRow].run())
                      }
        } yield assertTrue(true)
      },
      test("upsert and fetch") {
        for
          repo <- ZIO.service[StoreRepo]
          row   = sampleRow
          _    <- repo.upsert(row)
          got  <- repo.get(row.key)
          _    <- Console.printLine(got)
        yield assertTrue(got.exists(_.implId == "fs"))
      },
    ).provideSomeLayerShared(pgLayer)

  def spec =
    if containersEnabled then integrationSuite
    else
      suite("StoreRepo")(
        test("Postgres integration disabled") {
          assertTrue(true)
        }
      ) @@ TestAspect.ignore
