package graviton.protocol.http

import graviton.protocol.http.BlobKeyIdParser.Error
import zio.test.*

object BlobKeyIdParserSpec extends ZIOSpecDefault:

  override def spec: Spec[TestEnvironment, Any] =
    suite("BlobKeyIdParser")(
      test("decodes and validates a canonical blob key") {
        val encoded = s"sha-256%3A${"a" * 64}%3A12"

        assertTrue(
          BlobKeyIdParser.validate(encoded).toEither.exists(_.bits.render == s"sha-256:${"a" * 64}:12")
        )
      },
      test("stops at malformed path encoding") {
        val result = BlobKeyIdParser.validate("%GG").toEither

        assertTrue(result.left.exists(_.toChunk == zio.Chunk(Error.InvalidEncoding)))
      },
      test("accumulates independent blob-id and content-key errors") {
        val result = BlobKeyIdParser.validate("x" * 257).toEither

        assertTrue(
          result.left.exists { errors =>
            errors.length == 2 &&
            errors.exists {
              case Error.InvalidBlobId(_) => true
              case _                      => false
            } &&
            errors.exists {
              case Error.InvalidContentKey(_) => true
              case _                          => false
            }
          }
        )
      },
      test("renders all accumulated diagnostics at the HTTP adapter") {
        val result = BlobKeyIdParser.parse("x" * 257)

        assertTrue(
          result.left.exists(_.contains("Invalid blob ID:")),
          result.left.exists(_.contains("Content key exceeds 128 characters")),
        )
      },
    )
