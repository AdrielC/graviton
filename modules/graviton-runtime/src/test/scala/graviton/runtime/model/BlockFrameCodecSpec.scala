package graviton.runtime.model

import graviton.core.attributes.BinaryAttributes
import graviton.core.bytes.Hasher
import graviton.core.codec.BinaryKeyCodec
import graviton.core.keys.{BinaryKey, KeyBits, ViewTransform}
import graviton.core.types.{FileSize, MaxBlockBytes}
import scodec.bits.BitVector
import scodec.codecs.{int32, int64, uint8}
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
      test("accepts the v1 wire and rejects unknown versions on direct and streaming paths") {
        for
          block        <- canonicalBlock("frame-version")
          frame        <- ZIO.fromEither(BlockFramer.synthesizeBlock(block, 0L, BlockWritePlan(), FrameContext()))
          encoded      <- ZIO.fromEither(BlockFrameCodec.codec.encode(frame).toEither.left.map(_.message))
          v1Bits       <- ZIO.fromEither(uint8.encode(1).toEither.left.map(_.message))
          unknownBits  <- ZIO.fromEither(uint8.encode(2).toEither.left.map(_.message))
          decoded      <- ZIO.fromEither(BlockFrameCodec.codec.decode(encoded).toEither.left.map(_.message))
          directEncode  = BlockFrameCodec.codec
                            .encode(frame.copy(header = frame.header.copy(version = 2)))
                            .toEither
                            .left
                            .map(_.message)
          directDecode  = BlockFrameCodec.codec.decode(unknownBits).toEither.left.map(_.message)
          streamEncode <- ZStream(frame.copy(header = frame.header.copy(version = 2)))
                            .via(BlockFrameStreams.encode)
                            .runDrain
                            .exit
          streamDecode <- ZStream
                            .fromChunk(Chunk.fromArray(unknownBits.toByteArray))
                            .via(BlockFrameStreams.decode)
                            .runDrain
                            .exit
        yield assertTrue(
          encoded.take(8L) == v1Bits,
          decoded.value == frame,
          directEncode.swap.exists(_.contains("Unsupported block frame version 2 (expected 1)")),
          directDecode.swap.exists(_.contains("Unsupported block frame version 2 (expected 1)")),
          streamEncode.isFailure,
          streamDecode.isFailure,
        )
      },
      test("rejects negative AAD block indexes on direct and streaming encode and decode") {
        for
          block             <- canonicalBlock("negative-block-index")
          frame             <- ZIO.fromEither(BlockFramer.synthesizeBlock(block, 0L, BlockWritePlan(), FrameContext()))
          encoded           <- ZIO.fromEither(BlockFrameCodec.codec.encode(frame).toEither.left.map(_.message))
          negativeIndexBits <- ZIO.fromEither(int64.encode(-1L).toEither.left.map(_.message))
          invalidFrame       = frame.copy(aad = frame.aad.copy(blockIndex = Some(-1L)))
          directEncode       = BlockFrameCodec.codec.encode(invalidFrame).toEither.left.map(_.message)
          streamEncode      <- ZStream(invalidFrame).via(BlockFrameStreams.encode).runDrain.exit
          // The synthesized v1 frame has absent key-id, nonce, org-id, and blob-key fields,
          // followed by a present block-index flag. Replace only its 64-bit index value.
          blockIndexOffset   = 8L + 8L + 8L + 64L + 32L + 1L + 1L + 1L + 1L + 1L
          negativeWire       = encoded.take(blockIndexOffset) ++ negativeIndexBits ++ encoded.drop(blockIndexOffset + 64L)
          directDecode       = BlockFrameCodec.codec.decode(negativeWire).toEither.left.map(_.message)
          streamDecode      <- ZStream
                                 .fromChunk(Chunk.fromArray(negativeWire.toByteArray))
                                 .via(BlockFrameStreams.decode)
                                 .runDrain
                                 .exit
        yield assertTrue(
          frame.header.keyId.isEmpty,
          frame.header.nonce.isEmpty,
          frame.aad.orgId.isEmpty,
          frame.aad.blobKey.isEmpty,
          frame.aad.blockIndex.contains(0L),
          directEncode.swap.exists(_.contains("AAD block index cannot be negative: -1")),
          directDecode.swap.exists(_.contains("AAD block index cannot be negative: -1")),
          streamEncode.isFailure,
          streamDecode.isFailure,
        )
      },
      test("stream pipelines encode and decode bytes") {
        for
          block1        <- canonicalBlock("stream-frame-one")
          block2        <- canonicalBlock("stream-frame-two")
          frame1        <- ZIO.fromEither(BlockFramer.synthesizeBlock(block1, 1L, BlockWritePlan(), FrameContext()))
          frame2        <- ZIO.fromEither(BlockFramer.synthesizeBlock(block2, 2L, BlockWritePlan(), FrameContext()))
          frames         = Chunk(frame1, frame2)
          frame1Bits    <- ZIO.fromEither(BlockFrameCodec.codec.encode(frame1).toEither.left.map(_.message))
          frame2Bits    <- ZIO.fromEither(BlockFrameCodec.codec.encode(frame2).toEither.left.map(_.message))
          encoded       <- ZStream.fromChunk(frames).via(BlockFrameStreams.encode).runCollect
          decoded       <- ZStream.fromChunk(encoded).via(BlockFrameStreams.decode).runCollect
          tiny          <- ZStream.fromChunk(encoded).rechunk(1).via(BlockFrameStreams.decode).runCollect
          seenPrefix    <- Ref.make(Chunk.empty[BlockFrame])
          truncated     <- ZStream
                             .fromChunk(encoded.dropRight(1))
                             .via(BlockFrameStreams.decode)
                             .tap(frame => seenPrefix.update(_ :+ frame))
                             .runDrain
                             .exit
          emittedPrefix <- seenPrefix.get
          encodedBits    = BitVector(encoded.toArray)
          dataBitCount   = frame1Bits.size + frame2Bits.size
          padding        = encodedBits.drop(dataBitCount)
        yield assertTrue(
          frame1Bits.size % 8L != 0L,
          frame2Bits.size % 8L != 0L,
          encodedBits.take(dataBitCount) == frame1Bits ++ frame2Bits,
          padding.size <= 7L,
          padding.populationCount == 0L,
          decoded == frames,
          tiny == frames,
          truncated.isFailure,
          emittedPrefix == Chunk(frame1),
        )
      },
      test("decode emits a complete small frame without waiting for 64 KiB or end of stream") {
        for
          block   <- canonicalBlock("first-frame-latency")
          frame   <- ZIO.fromEither(BlockFramer.synthesizeBlock(block, 0L, BlockWritePlan(), FrameContext()))
          encoded <- ZStream(frame).via(BlockFrameStreams.encode).runCollect
          first   <- (ZStream.fromChunk(encoded) ++ ZStream.never)
                       .via(BlockFrameStreams.decode)
                       .take(1)
                       .runHead
                       .timeout(1.second)
        yield assertTrue(first.contains(Some(frame)))
      },
      test("encode preflights oversized public frame fields before scodec materialization") {
        for
          block              <- canonicalBlock("preflight-frame")
          frame              <- ZIO.fromEither(BlockFramer.synthesizeBlock(block, 0L, BlockWritePlan(), FrameContext()))
          oversizedPayload    = Chunk.fill(MaxBlockBytes + 1)(0.toByte)
          oversized           = frame.copy(
                                  header = frame.header.copy(payloadLength = oversizedPayload.length.toLong),
                                  ciphertext = oversizedPayload,
                                )
          oversizedValidation = BlockFrameStreams.validateForEncoding(oversized)
          oversizedExit      <- ZStream(oversized).via(BlockFrameStreams.encode).runDrain.exit
          oversizedKey        = frame.copy(header = frame.header.copy(keyId = Some("x" * 4097)))
          keyValidation       = BlockFrameStreams.validateForEncoding(oversizedKey)
          keyExit            <- ZStream(oversizedKey).via(BlockFrameStreams.encode).runDrain.exit
        yield assertTrue(
          oversizedValidation.swap.exists(_.contains("ciphertext exceeds")),
          oversizedExit.isFailure,
          keyValidation.swap.exists(_.contains("key id exceeds")),
          keyExit.isFailure,
        )
      },
      test("direct codec and AAD rendering use the same preflight as the stream") {
        for
          block         <- canonicalBlock("direct-codec-preflight")
          frame         <- ZIO.fromEither(BlockFramer.synthesizeBlock(block, 0L, BlockWritePlan(), FrameContext()))
          oversizedKey   = frame.copy(header = frame.header.copy(keyId = Some("k" * 4097)))
          oversizedNonce = frame.copy(header = frame.header.copy(nonce = Some(Chunk.fill(1025)(1.toByte))))
          oversizedTag   = frame.copy(tag = Some(Chunk.fill(1025)(2.toByte)))
          oversizedAad   = frame.aad.copy(extra = Chunk(FrameAadEntry("a" * 4097, "value")))
          aadRender      = BlockFrameCodec.renderAadBytes(oversizedAad)
          oversizedFrame = frame.copy(aad = oversizedAad)
        yield assertTrue(
          BlockFrameCodec.codec.encode(oversizedKey).toEither.isLeft,
          BlockFrameCodec.codec.encode(oversizedNonce).toEither.isLeft,
          BlockFrameCodec.codec.encode(oversizedTag).toEither.isLeft,
          aadRender.swap.exists(_.contains("AAD entry 0 key exceeds")),
          BlockFrameCodec.codec.encode(oversizedFrame).toEither.isLeft,
        )
      },
      test("BlockFramer rejects oversized context before AAD UTF-8 materialization") {
        for
          block <- canonicalBlock("framer-preflight")
          result = BlockFramer.synthesizeBlock(
                     block,
                     0L,
                     BlockWritePlan(),
                     FrameContext(orgId = Some("o" * 4097)),
                   )
        yield assertTrue(result.swap.exists(_.contains("AAD organization id exceeds")))
      },
      test("aggregate AAD size is rejected before encoding") {
        for
          block       <- canonicalBlock("aggregate-aad-preflight")
          frame       <- ZIO.fromEither(BlockFramer.synthesizeBlock(block, 0L, BlockWritePlan(), FrameContext()))
          maxText      = "x" * 4096
          oversizedAad = frame.aad.copy(extra = Chunk.fill(128)(FrameAadEntry(maxText, maxText)))
          aadCheck     = BlockFrameCodec.validateAadForEncoding(oversizedAad)
          rendered     = BlockFrameCodec.renderAadBytes(oversizedAad)
          direct       = BlockFrameCodec.codec.encode(frame.copy(aad = oversizedAad)).toEither
        yield assertTrue(
          aadCheck.swap.exists(_.contains("encoded AAD exceed")),
          rendered.swap.exists(_.contains("encoded AAD exceed")),
          direct.isLeft,
        )
      },
      test("view transforms are capped before BinaryKeyCodec allocates sorted copies") {
        for
          block          <- canonicalBlock("view-argument-preflight")
          frame          <- ZIO.fromEither(BlockFramer.synthesizeBlock(block, 0L, BlockWritePlan(), FrameContext()))
          transform      <- ZIO.fromEither(
                              ViewTransform.from(
                                "bounded_view",
                                (0 until 129).map(index => s"arg_$index" -> "value").toMap,
                                None,
                              )
                            )
          base           <- ZIO.fromEither(BinaryKey.manifest(block.key.bits))
          smallTransform <- ZIO.fromEither(
                              ViewTransform.from(
                                "small_view",
                                Map("font" -> "mono", "page" -> "1"),
                                Some("preview"),
                              )
                            )
          smallView      <- ZIO.fromEither(BinaryKey.view(base, smallTransform))
          view            = BinaryKey.View(block.key.bits, base, transform)
          keyResult       = BinaryKeyCodec.codec.encode(view).toEither.left.map(_.message)
          aadResult       = BlockFrameCodec.renderAadBytes(frame.aad.copy(blobKey = Some(view)))
        yield assertTrue(
          BinaryKeyCodec.codec.encode(smallView).toEither.isRight,
          BlockFrameCodec.renderAadBytes(frame.aad.copy(blobKey = Some(smallView))).isRight,
          keyResult.swap.exists(_.contains("view argument count exceeds 128")),
          aadResult.swap.exists(_.contains("view argument count exceeds 128")),
        )
      },
      test("declared AAD window fails without pulling an unbounded tail") {
        for
          block         <- canonicalBlock("malicious-aad-window")
          frame         <- ZIO.fromEither(BlockFramer.synthesizeBlock(block, 0L, BlockWritePlan(), FrameContext()))
          encoded       <- ZIO.fromEither(BlockFrameCodec.codec.encode(frame).toEither.left.map(_.message))
          oneByteLength <- ZIO.fromEither(int32.encode(1).toEither.left.map(_.message))
          malformed      = encoded.take(88L) ++ oneByteLength ++ encoded.drop(120L)
          finitePrefix   = malformed.take(130L).toByteArray
          exit          <- (ZStream.fromChunk(Chunk.fromArray(finitePrefix)) ++ ZStream.never)
                             .via(BlockFrameStreams.decode)
                             .runDrain
                             .exit
                             .timeout(1.second)
        yield assertTrue(exit.exists(_.isFailure))
      },
      test("encoded output remains bit exact while each emitted chunk is bounded") {
        for
          block     <- canonicalBlock(Chunk.fill(BlockFrameStreams.OutputChunkBytes * 3)(0x5a.toByte))
          frame     <- ZIO.fromEither(BlockFramer.synthesizeBlock(block, 0L, BlockWritePlan(), FrameContext()))
          frameBits <- ZIO.fromEither(BlockFrameCodec.codec.encode(frame).toEither.left.map(_.message))
          chunks    <- ZStream(frame).via(BlockFrameStreams.encode).chunks.runCollect
          encoded    = chunks.flatten
        yield assertTrue(
          chunks.length > 1,
          chunks.forall(_.length <= BlockFrameStreams.OutputChunkBytes),
          encoded == Chunk.fromArray(frameBits.toByteArray),
        )
      },
    )

  private def canonicalBlock(label: String): IO[Throwable, CanonicalBlock] =
    canonicalBlock(Chunk.fromArray(label.getBytes(StandardCharsets.UTF_8)))

  private def canonicalBlock(bytes: Chunk[Byte]): IO[Throwable, CanonicalBlock] =
    for
      hasher <- ZIO.fromEither(Hasher.systemDefault).mapError(err => new IllegalStateException(err))
      algo    = hasher.algo
      _       = hasher.update(bytes.toArray)
      digest <- ZIO.fromEither(hasher.digest).mapError(msg => new IllegalArgumentException(msg))
      bits   <- ZIO
                  .fromEither(KeyBits.create(algo, digest, bytes.length.toLong))
                  .mapError(msg => new IllegalArgumentException(msg))
      key    <- ZIO.fromEither(BinaryKey.block(bits)).mapError(msg => new IllegalArgumentException(msg))
      size   <- ZIO.fromEither(FileSize.either(bytes.length.toLong)).mapError(msg => new IllegalArgumentException(msg))
      attrs   = BinaryAttributes.empty.confirmSize(size)
      block  <- ZIO
                  .fromEither(CanonicalBlock.make(key, bytes, attrs))
                  .mapError(msg => new IllegalArgumentException(msg))
    yield block
