package graviton.blob

import graviton.blob.Types.*
import io.github.iltotore.iron.autoRefine
import zio.test.*

import java.time.Instant

object BinaryModelSpec extends ZIOSpecDefault:
  private val sha256Hash: HexLower = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
  private val sampleMime: Mime     = "application/octet-stream"

  def spec =
    suite("BinaryModel")(
      test("BinaryKey.make enforces digest length per algorithm") {
        val key = BinaryKey.make("sha-256", sha256Hash, 0L, Some(sampleMime))
        val bad = BinaryKey.make("md5", sha256Hash, 0L, None)
        assertTrue(key.isRight, bad == Left("md5 requires 32 hex chars, got 64"))
      },
      test("Tracked.merge favours higher precedence and newer values") {
        val baseMime: Mime     = "text/plain"
        val overrideMime: Mime = "text/plain; charset=utf-8"
        val sniffed            = Tracked(baseMime, Source.Sniffed, Instant.parse("2024-01-01T00:00:00Z"))
        val provided           = Tracked(baseMime, Source.ProvidedUser, Instant.parse("2023-12-01T00:00:00Z"))
        val newer              = Tracked(overrideMime, Source.ProvidedUser, Instant.parse("2024-02-01T00:00:00Z"))

        assertTrue(
          Tracked.merge(sniffed, provided) == provided,
          Tracked.merge(provided, newer) == newer,
        )
      },
      test("BinaryAttributes merge and diff track provenance") {
        val derivedDigest: HexLower = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        val sniffedMime: Mime       = "text/plain"
        val overrideMime: Mime      = "text/plain; charset=utf-8"
        val base                    = BinaryAttributes.empty
          .upsertDigest("sha-256", Tracked(derivedDigest, Source.Derived, Instant.parse("2024-01-01T00:00:00Z")))
          .upsertMime(Tracked(sniffedMime, Source.Sniffed, Instant.parse("2024-01-01T00:00:00Z")))

        val updated = base
          .upsertMime(Tracked(overrideMime, Source.ProvidedUser, Instant.parse("2024-01-02T00:00:00Z")))
          .record("user-override")

        val diff = base.diff(updated)
        assertTrue(
          updated.mime.exists(_.source == Source.ProvidedUser),
          updated.history.nonEmpty,
          diff.mime.exists(_._2.exists(_.value == "text/plain; charset=utf-8")),
          diff.digests.isEmpty,
        )
      },
    )
