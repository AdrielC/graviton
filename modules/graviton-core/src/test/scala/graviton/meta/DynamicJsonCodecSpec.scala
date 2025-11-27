package graviton.meta

import zio.ZIO
import zio.json.ast.Json
import zio.schema.{DeriveSchema, Schema}
import zio.test.*

object DynamicJsonCodecSpec extends ZIOSpecDefault:

  final case class UploadMeta(source: String, originalName: String, chunkCount: Long)
  given Schema[UploadMeta] = DeriveSchema.gen[UploadMeta]

  override def spec =
    suite("DynamicJsonCodec")(
      test("round-trips typed meta through DynamicValue.Record") {
        val meta   = UploadMeta("scanner", "scan.pdf", 2L)
        val schema = summon[Schema[UploadMeta]]
        for
          record  <- ZIO.fromEither(DynamicRecordCodec.toRecord(schema, meta))
          json    <- ZIO.fromEither(DynamicJsonCodec.encodeDynamic(schema, record))
          decoded <- ZIO.fromEither(DynamicJsonCodec.decodeDynamicRecord(schema, json))
        yield assertTrue(decoded == record, json.isInstanceOf[Json.Obj])
      },
      test("fails decoding when JSON breaks the schema") {
        val json   = Json.Obj("unexpected" -> Json.Str("value"))
        val result =
          DynamicJsonCodec.decodeDynamicRecord(summon[Schema[UploadMeta]], json)
        assertTrue(result.isLeft)
      },
    )
