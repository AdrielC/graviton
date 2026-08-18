package graviton.codec

import zio.*
import zio.stream.ZChannel
import zio.test.*

object StreamCodecSpec extends ZIOSpecDefault:
  override def spec: Spec[TestEnvironment & Scope, Any] =
    suite("StreamCodec")(
      test("buffers leftovers until a record completes") {
        val codec          = StreamCodec.fixedSize[Int](3)(decodeInt24)
        val first          = chunk(0x00, 0x01)
        val (logs1, res1)  = codec.onChunk(first).runAll(codec.initial)
        val (state1, out1) = res1.toOption.get

        assertTrue(out1.isEmpty) &&
        assertTrue(state1.buffer.length == 2) &&
        assertTrue(state1.flags.contains(CodecFlag.NeedsMoreInput)) &&
        assertTrue(logs1.nonEmpty)
      },
      test("decodes values once enough bytes arrive") {
        val codec          = StreamCodec.fixedSize[Int](3)(decodeInt24)
        val first          = chunk(0x00, 0x01)
        val (_, res1)      = codec.onChunk(first).runAll(codec.initial)
        val (state1, _)    = res1.toOption.get
        val second         = chunk(0x02, 0x03, 0x04)
        val (_, res2)      = codec.onChunk(second).runAll(state1)
        val (state2, out2) = res2.toOption.get

        val emitted = out2.flatMap(_.fold(Chunk.empty[Int], _ => Chunk.empty, identity))
        assertTrue(emitted == Chunk(0x000102)) &&
        assertTrue(state2.buffer.length == 2) &&
        assertTrue(state2.flags.contains(CodecFlag.NeedsMoreInput))
      },
      test("reports fatal decoder failures") {
        val codec            = StreamCodec.fixedSize[Int](3) { bytes =>
          decodeInt24(bytes).flatMap { value =>
            if value == 0x000102 then Left("boom") else Right(value)
          }
        }
        val chunkData        = chunk(0x00, 0x01, 0x02, 0x03, 0x04)
        val (_, res)         = codec.onChunk(chunkData).runAll(codec.initial)
        val (state, outputs) = res.toOption.get

        val failure = outputs.find(_.isFailure)
        assertTrue(failure.isDefined) &&
        assertTrue(state.status == StreamStatus.Failed(StreamCodecError.DecoderFailure("boom")))
      },
      test("fails when stream ends with leftover bytes") {
        val codec             = StreamCodec.fixedSize[Int](3)(decodeInt24)
        val chunkData         = chunk(0x00, 0x01)
        val (_, midRes)       = codec.onChunk(chunkData).runAll(codec.initial)
        val (midState, _)     = midRes.toOption.get
        val (_, finalRes)     = codec.onEnd.runAll(midState)
        val (finalState, out) = finalRes.toOption.get

        val fail = out.find(_.isFailure)
        val end  = out.find(_.isDone)
        assertTrue(fail.isDefined) &&
        assertTrue(end.isDefined) &&
        assertTrue(finalState.status == StreamStatus.Failed(StreamCodecError.UnexpectedEnd(2, 3)))
      },
      test("channel forwards all takes from a chunk") {
        val codec    = StreamCodec.fixedSize[Int](3)(decodeInt24)
        val upstream = (ZChannel.write(chunk(0x00, 0x01)) *> ZChannel.unit) >>> codec.toChannel

        for
          result  <- upstream.runCollect
          (out, _) = result
          fail     = out.find(_.isFailure)
          end      = out.find(_.isDone)
        yield assertTrue(fail.isDefined) && assertTrue(end.isDefined)
      },
    )

  private def decodeInt24(bytes: Chunk[Byte]): Either[String, Int] =
    if bytes.length != 3 then Left(s"expected 3 bytes, received ${bytes.length}")
    else
      val b0 = bytes(0) & 0xff
      val b1 = bytes(1) & 0xff
      val b2 = bytes(2) & 0xff
      Right((b0 << 16) | (b1 << 8) | b2)

  private def chunk(bytes: Int*): Chunk[Byte] =
    Chunk.fromArray(bytes.map(_.toByte).toArray)
