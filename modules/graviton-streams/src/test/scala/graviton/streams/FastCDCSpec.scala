package graviton.streams

import graviton.core.model.Block
import zio.*
import zio.stream.ZStream
import zio.test.*

object FastCDCSpec extends ZIOSpecDefault:

  def spec: Spec[TestEnvironment & Scope, Any] =
    suite("FastCDCSpec")(
      test("respects bounds for intermediate chunks") {
        val cfg     = FastCDC.Config(
          minBytes = 64,
          avgBytes = 128,
          maxBytes = 256,
          normalization = FastCDC.NormalizationLevel.Level2,
        )
        val payload = Chunk.fromArray(Array.fill(8 * cfg.maxBytes)(0x42.toByte))

        for {
          blocks <- ZStream.fromChunk(payload).via(FastCDC.chunker(cfg)).runCollect
        } yield {
          val prefix   = if blocks.length <= 1 then Chunk.empty[Block] else blocks.dropRight(1)
          val last     = blocks.lastOption
          val prefixOk = prefix.forall { block =>
            block.length >= cfg.minBytes && block.length <= cfg.maxBytes
          }
          val lastOk   = last.forall(_.length <= cfg.maxBytes)
          assertTrue(prefixOk && lastOk)
        }
      },
      test("produces deterministic splits for identical payloads") {
        val cfg     = FastCDC.Config(
          minBytes = 32,
          avgBytes = 96,
          maxBytes = 192,
          normalization = FastCDC.NormalizationLevel.Level1,
        )
        val payload = Chunk.fromArray(Array.tabulate(4096)(idx => (idx % 251).toByte))

        for {
          first  <- ZStream.fromChunk(payload).via(FastCDC.chunker(cfg)).map(_.length).runCollect
          second <- ZStream.fromChunk(payload).via(FastCDC.chunker(cfg)).map(_.length).runCollect
        } yield assertTrue(first == second)
      },
      test("enforces hard max even without boundaries") {
        val cfg   = FastCDC.Config(
          minBytes = 8,
          avgBytes = 16,
          maxBytes = 32,
          normalization = FastCDC.NormalizationLevel.Level0,
        )
        val bytes = Chunk.fromArray(Array.fill(cfg.maxBytes * 5 + 7)(1.toByte))

        for {
          blocks <- ZStream.fromChunk(bytes).via(FastCDC.chunker(cfg)).runCollect
        } yield assertTrue(blocks.forall(_.length <= cfg.maxBytes))
      },
    )
