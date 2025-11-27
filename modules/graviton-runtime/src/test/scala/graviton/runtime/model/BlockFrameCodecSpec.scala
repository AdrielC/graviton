package graviton.runtime.model

import graviton.core.attributes.{BinaryAttributes, Source, Tracked}
import graviton.core.bytes.Hasher
import graviton.core.keys.{BinaryKey, KeyBits}
import graviton.core.model.ByteConstraints
import zio.*
import zio.stream.ZStream
import zio.Chunk
import zio.test.*

import java.nio.charset.StandardCharsets

object BlockFrameCodecSpec extends ZIOSpecDefault:

  override def spec =
    suite("BlockFrameCodec")(
      test("round-trips individual frames") {
        for
          block   <- canonicalBlock("roundtrip-frame")
          frame   <- ZIO.fromEither(BlockFramer.synthesizeBlock(block, 0L, BlockWritePlan(), FrameContext()))
          bits     = BlockFrameCodec.codec.encode(frame).toEither.left.map(_.message)
          decoded <-
            ZIO.fromEither(bits.flatMap(encoded => BlockFrameCodec.codec.decode(encoded).toEither.left.map(_.message)).map(_.value))
        yield assertTrue(decoded == frame)
      },
      test("stream pipelines encode and decode bytes") {
        for
          block   <- canonicalBlock("stream-frame")
          frame   <- ZIO.fromEither(BlockFramer.synthesizeBlock(block, 1L, BlockWritePlan(), FrameContext()))
          encoded <- ZStream(frame).via(BlockFrameStreams.encode).runCollect
          decoded <- ZStream.fromChunk(encoded).via(BlockFrameStreams.decode).runCollect
        yield assertTrue(decoded == Chunk(frame))
      },
    )

  private def canonicalBlock(label: String): IO[Throwable, CanonicalBlock] =
    val bytes = Chunk.fromArray(label.getBytes(StandardCharsets.UTF_8))
    for
      algoHasher    <- ZIO.fromEither(Hasher.systemDefault).mapError(err => new IllegalStateException(err))
      (algo, hasher) = algoHasher
      _              = hasher.update(bytes.toArray)
      digest        <- ZIO.fromEither(hasher.result).mapError(msg => new IllegalArgumentException(msg))
      bits          <- ZIO
                         .fromEither(KeyBits.create(algo, digest, bytes.length.toLong))
                         .mapError(msg => new IllegalArgumentException(msg))
      key           <- ZIO.fromEither(BinaryKey.block(bits)).mapError(msg => new IllegalArgumentException(msg))
      attrs          = BinaryAttributes.empty.confirmSize(
                         Tracked.now(ByteConstraints.unsafeFileSize(bytes.length.toLong), Source.Derived)
                       )
      block         <- ZIO
                         .fromEither(CanonicalBlock.make(key, bytes, attrs))
                         .mapError(msg => new IllegalArgumentException(msg))
    yield block
