package graviton.shared

import graviton.shared.ApiModels.*
import zio.blocks.mediatype.MediaType
import zio.blocks.schema.json.JsonFormat
import zio.json.*
import zio.test.*

object ApiContract:
  private val id = BlobId.applyUnsafe(
    "sha-256:ca978112ca1bbdcafac231b39a23dc4da786eff8147c4e72b9807785afee48bb:1"
  )

  private val summary = BlobSummary(
    id = id,
    size = SizeBytes.applyUnsafe(1L),
    createdAt = 1_700_000_000_000L,
    digest = "ca978112ca1bbdcafac231b39a23dc4da786eff8147c4e72b9807785afee48bb",
    blockCount = Count.applyUnsafe(1L),
  )

  private val upload = BlobUploadResult(
    blob = summary,
    freshBlocks = Count.applyUnsafe(1L),
    duplicateBlocks = Count.applyUnsafe(0L),
    durationSeconds = 0.25,
  )

  val spec =
    suite("ZIO Blocks shared API contract")(
      test("round-trips nested refined API values") {
        val encoded = ApiJson.encode(upload)

        assertTrue(ApiJson.decode[BlobUploadResult](encoded) == Right(upload))
      },
      test("remains wire-compatible with the existing zio-json contract") {
        val blocksJson = ApiJson.encode(upload)
        val legacyJson = upload.toJson

        assertTrue(
          blocksJson.fromJson[BlobUploadResult] == Right(upload),
          ApiJson.decode[BlobUploadResult](legacyJson) == Right(upload),
        )
      },
      test("matches every released zio-json wire shape, including empty collections and zero values") {
        val health       = HealthResponse("ok", "0.4.0", 0L)
        val stats        = SystemStats(Count.applyUnsafe(0L), SizeBytes.applyUnsafe(0L), Count.applyUnsafe(0L), Ratio.applyUnsafe(0.0))
        val block        = BlobBlock(
          Count.applyUnsafe(0L),
          id.value,
          SizeBytes.applyUnsafe(0L),
          SizeBytes.applyUnsafe(1L),
        )
        val emptyDetails = BlobDetails(summary, Nil)
        val emptyList    = BlobListResponse(Nil, None)
        val nextList     = BlobListResponse(List(summary), Some(id.value))
        val emptyUpload  = upload.copy(
          freshBlocks = Count.applyUnsafe(0L),
          duplicateBlocks = Count.applyUnsafe(0L),
          durationSeconds = 0.0,
        )
        val verification = BlobVerificationResult(id, verified = false, bytesChecked = SizeBytes.applyUnsafe(0L))
        val apiError     = ApiError("invalid_blob", "Invalid blob")

        assertTrue(
          ApiJson.encode(health) == health.toJson,
          ApiJson.encode(stats) == stats.toJson,
          ApiJson.encode(summary) == summary.toJson,
          ApiJson.encode(block) == block.toJson,
          ApiJson.encode(emptyDetails) == emptyDetails.toJson,
          ApiJson.encode(emptyList) == emptyList.toJson,
          ApiJson.encode(nextList) == nextList.toJson,
          ApiJson.encode(emptyUpload) == emptyUpload.toJson,
          ApiJson.encode(verification) == verification.toJson,
          ApiJson.encode(apiError) == apiError.toJson,
          ApiJson.decode[BlobListResponse](emptyList.toJson) == Right(emptyList),
          ApiJson.decode[BlobDetails](emptyDetails.toJson) == Right(emptyDetails),
          ApiJson.decode[BlobListResponse]("{}").isLeft,
          ApiJson.decode[BlobDetails](s"{\"summary\":${summary.toJson}}").isLeft,
        )
      },
      test("rejects invalid Iron wrappers during Blocks decode") {
        val oversized     = summary
          .copy(size = SizeBytes.applyUnsafe(1L))
          .toJson
          .replace("\"size\":1", "\"size\":1099511627777")
        val negativeCount = summary.toJson.replace("\"blockCount\":1", "\"blockCount\":-1")

        assertTrue(
          ApiJson.decode[BlobSummary](oversized).isLeft,
          ApiJson.decode[BlobSummary](negativeCount).isLeft,
        )
      },
      test("derives an inspectable structural JSON Schema from the wire contract") {
        val schema = ApiJson.jsonSchema[BlobUploadResult].toString

        assertTrue(
          schema.contains("blob"),
          schema.contains("freshBlocks"),
          schema.contains("duplicateBlocks"),
          schema.contains("durationSeconds"),
        )
      },
      test("round-trips canonical media type parameters") {
        val raw    = "application/pdf; charset=utf-8; profile=archive"
        val parsed = MediaTypeText.parse(raw)
        val upper  = MediaTypeText.parse("APPLICATION/PDF; X-IDENTIFIER=ASCII")

        assertTrue(
          parsed.map(MediaTypeText.render) == Right(raw),
          upper.map(MediaTypeText.render) == Right("application/pdf; x-identifier=ASCII"),
          parsed.flatMap(value => MediaTypeText.parse(MediaTypeText.render(value))).map(_.parameters) ==
            Right(Map("charset" -> "utf-8", "profile" -> "archive")),
          MediaTypeText.parse("application/pdf; charset").isLeft,
        )
      },
      test("handles quoted separators and escapes without accepting controls") {
        val raw     = "application/example; name=\"a;b\"; note=\"a\\\"b\""
        val parsed  = MediaTypeText.parse(raw)
        val control = "application/example; name=\"a\nb\""

        assertTrue(
          parsed.flatMap(MediaTypeText.renderEither) == Right(raw),
          parsed.map(_.parameters) == Right(Map("name" -> "\"a;b\"", "note" -> "\"a\\\"b\"")),
          MediaTypeText.parse(control).isLeft,
          MediaTypeText.parse("application/pdf\r\n").isLeft,
          MediaTypeText.parse("\u0000application/pdf").isLeft,
          MediaTypeText.renderEither(MediaType(" application", "pdf")).isLeft,
          MediaTypeText.renderEither(MediaType("application", "pdf\r\n")).isLeft,
        )
      },
      test("enforces its input and rendered storage bound") {
        val prefix              = "application/x; n="
        val exact               = prefix + ("x" * (MediaTypeText.MaxWireLength - prefix.length))
        val over                = exact + "x"
        val invalidProgrammatic = MediaType("application", "pdf", parameters = Map("name" -> "\"line\nfeed\""))
        val hugeMainType        = MediaType("a" * (MediaTypeText.MaxWireLength + 1), "pdf")
        val hugeParameterName   = MediaType(
          "application",
          "pdf",
          parameters = Map("a" * (MediaTypeText.MaxWireLength + 1) -> "x"),
        )
        val parameterNames      = "abcdefghijklmnopqrstuvwxyz0123456789!#$%&'*+.^_`|~-"
        val fiftyParameters     = "a/b" + parameterNames.take(50).map(name => s";$name=x").mkString
        val fiftyOneParameters  = "a/b" + parameterNames.take(51).map(name => s";$name=x").mkString

        assertTrue(
          exact.length == MediaTypeText.MaxWireLength,
          MediaTypeText.parse(exact).isRight,
          MediaTypeText.parse(over).isLeft,
          MediaTypeText.parse("application/pdf" + (" " * MediaTypeText.MaxWireLength)).isLeft,
          MediaTypeText.renderEither(invalidProgrammatic).isLeft,
          MediaTypeText.renderEither(hugeMainType).isLeft,
          MediaTypeText.renderEither(hugeParameterName).isLeft,
          MediaTypeText.parse(fiftyParameters).flatMap(MediaTypeText.renderEither).isRight,
          MediaTypeText.parse(fiftyOneParameters).isLeft,
        )
      },
      test("exposes an operational ZIO Blocks media type schema") {
        val value = MediaTypeText.parse("application/pdf; profile=archive").toOption.get
        val codec = MediaTypeText.mediaTypeSchema.derive(JsonFormat)
        val json  = codec.encodeToString(value)

        assertTrue(codec.decode(json).map(MediaTypeText.render) == Right("application/pdf; profile=archive"))
      },
      test("serializes the typed error envelope") {
        val value = ApiError("invalid_blob", "Invalid blob")

        assertTrue(
          ApiJson.decode[ApiError](ApiJson.encode(value)) == Right(value),
          ApiJson.encode(value).fromJson[ApiError] == Right(value),
        )
      },
    )

end ApiContract
