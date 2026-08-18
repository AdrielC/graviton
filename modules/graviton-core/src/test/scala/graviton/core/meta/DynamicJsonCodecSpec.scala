package graviton.core.meta

import zio.json.ast.Json
import zio.schema.DeriveSchema
import zio.test.*

object DynamicJsonCodecSpec extends ZIOSpecDefault:

  final case class TestMeta(
    source: String,
    originalName: String,
    clientIp: String,
  )

  object TestMeta:
    val schema = DeriveSchema.gen[TestMeta]

  override def spec =
    suite("DynamicJsonCodecSpec")(
      test("encode and decode dynamic records for known schema") {
        val meta    = TestMeta("scanner", "scan123.pdf", "1.2.3.4")
        val dynamic = SchemaDef.toDynamicRecord(TestMeta.schema, meta).toOption.get

        val jsonEither     = DynamicJsonCodec.encodeDynamic(TestMeta.schema, dynamic)
        val expectedJson   = Json.Obj(
          "source"       -> Json.Str("scanner"),
          "originalName" -> Json.Str("scan123.pdf"),
          "clientIp"     -> Json.Str("1.2.3.4"),
        )
        val jsonAssertions = assertTrue(
          jsonEither.isRight &&
            jsonEither.toOption.contains(expectedJson)
        )

        val decoded = jsonEither.flatMap(DynamicJsonCodec.decodeDynamicRecord(TestMeta.schema, _))
        jsonAssertions && assertTrue(decoded == Right(dynamic))
      }
    )
