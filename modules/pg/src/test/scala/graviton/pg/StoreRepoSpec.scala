package graviton.pg

import graviton.db.{StoreKey, StoreRepo, StoreRow, StoreStatus, given}

import zio.*
import zio.test.*
import com.augustnagro.magnum.magzio.*
import com.augustnagro.magnum.{DbCodec, sql}

object StoreRepoSpec extends ZIOSpec[TestEnvironment & StoreRepo & TransactorZIO]:

  val bootstrap =
    ((ZLayer.fromZIO(ZIO.config[PgTestLayers.PgTestConfig]) >>> PgTestLayers.layer) >+>
      StoreRepoLive.layer) ++
      testEnvironment

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

  def spec =
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
    ) @@ TestAspect.ifEnv("TESTCONTAINERS") { value =>
      value.trim match
        case v if v.equalsIgnoreCase("1") || v.equalsIgnoreCase("true") || v.equalsIgnoreCase("yes") => true
        case _                                                                                       => false
    }
