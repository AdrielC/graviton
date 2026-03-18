package graviton.core

import zio.test.*

object GravitonErrorSpec extends ZIOSpecDefault:

  override def spec =
    suite("GravitonError")(
      suite("GravitonException round-trip")(
        test("preserves ValidationError through toThrowable/fromThrowable") {
          val original = GravitonError.ValidationError("bad field", field = Some("name"))
          val thrown   = GravitonException(original)
          val back     = thrown.error
          assertTrue(
            back == original,
            back.isInstanceOf[GravitonError.ValidationError],
            back.asInstanceOf[GravitonError.ValidationError].field.contains("name"),
          )
        },
        test("preserves StoreError through toThrowable/fromThrowable") {
          val cause    = new java.io.IOException("disk full")
          val original = GravitonError.StoreError("write failed", Some(cause))
          val thrown   = GravitonException(original)
          val back     = thrown.error
          assertTrue(
            back == original,
            back.isInstanceOf[GravitonError.StoreError],
            back.cause.contains(cause),
          )
        },
        test("preserves ChunkerError through round-trip") {
          val original = GravitonError.ChunkerError("invalid chunk size")
          val thrown   = GravitonException(original)
          val back     = thrown.error
          assertTrue(back == original)
        },
        test("preserves CodecError through round-trip") {
          val original = GravitonError.CodecError("bad protobuf", context = Some("manifest"))
          val thrown   = GravitonException(original)
          val back     = thrown.error
          assertTrue(
            back == original,
            back.asInstanceOf[GravitonError.CodecError].context.contains("manifest"),
          )
        },
        test("preserves ConfigError through round-trip") {
          val original = GravitonError.ConfigError("missing key")
          val thrown   = GravitonException(original)
          val back     = thrown.error
          assertTrue(back == original)
        },
        test("preserves InternalError through round-trip") {
          val original = GravitonError.InternalError("unexpected state")
          val thrown   = GravitonException(original)
          val back     = thrown.error
          assertTrue(back == original)
        },
      ),
      suite("fromThrowable")(
        test("wraps plain exception as InternalError") {
          val t    = new RuntimeException("boom")
          val back = GravitonError.fromThrowable(t)
          assertTrue(
            back.isInstanceOf[GravitonError.InternalError],
            back.message == "boom",
            back.cause.contains(t),
          )
        },
        test("recovers typed error from GravitonException") {
          val original = GravitonError.StoreError("not found")
          val thrown   = original.toThrowable
          val back     = GravitonError.fromThrowable(thrown)
          assertTrue(back == original)
        },
        test("handles null message in exception") {
          val t    = new RuntimeException(null: String)
          val back = GravitonError.fromThrowable(t)
          assertTrue(back.message == "RuntimeException")
        },
      ),
      suite("fromString")(
        test("creates ValidationError from string") {
          val err = GravitonError.fromString("not valid")
          assertTrue(
            err.isInstanceOf[GravitonError.ValidationError],
            err.message == "not valid",
          )
        }
      ),
      suite("toThrowable")(
        test("GravitonException created from string has InternalError") {
          val ex   = GravitonException("plain msg")
          val back = ex.error
          assertTrue(
            back.isInstanceOf[GravitonError.InternalError],
            back.message == "plain msg",
          )
        },
        test("GravitonException created from string+cause has InternalError with cause") {
          val cause = new RuntimeException("root")
          val ex    = GravitonException("wrapped", cause)
          val back  = ex.error
          assertTrue(
            back.isInstanceOf[GravitonError.InternalError],
            back.cause.contains(cause),
          )
        },
      ),
    )
