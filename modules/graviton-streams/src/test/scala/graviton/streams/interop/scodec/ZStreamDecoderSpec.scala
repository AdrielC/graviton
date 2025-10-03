package graviton.streams.interop.scodec

import zio.*
import zio.test.*
import zio.stream.*
import zio.ChunkBuilder
import zio.Exit

import java.nio.charset.{Charset, StandardCharsets}

import scodec.bits.BitVector
import scodec.codecs
import scodec.{Attempt, DecodeResult, Decoder, Err}

object ZStreamDecoderSpec extends ZIOSpecDefault:

  private val int32Codec   = codecs.int32
  private val int32Decoder = int32Codec.asDecoder

  private val int8Codec = codecs.int8

  private val positiveByteDecoder: Decoder[Int] =
    int8Codec.asDecoder.emap { value =>
      if value >= 0 then Attempt.successful(value)
      else Attempt.failure(Err("negative value"))
    }

  override def spec: Spec[TestEnvironment & Scope, Any] =
    suite("ZStreamDecoder")(
      test("once decodes a single value across chunk boundaries") {
        val encoded = int32Codec.encode(0xdeadbeef).require
        val chunks  = split(encoded, 5, 11, 7, 9)

        val effect =
          ZStream
            .fromIterable(chunks)
            .via(ZStreamDecoder.once(int32Decoder))
            .runCollect

        assertZIO(effect)(Assertion.equalTo(Chunk(0xdeadbeef)))
      },
      test("many decodes multiple values with irregular chunk sizes") {
        val encoded = Chunk(1, 2, 3).map(int32Codec.encode(_).require).reduce(_ ++ _)
        val chunks  = split(encoded, 4, 3, 16, 5, 7, 9, 6, 6)

        val effect =
          ZStream
            .fromIterable(chunks)
            .via(ZStreamDecoder.many(int32Decoder))
            .runCollect

        assertZIO(effect)(Assertion.equalTo(Chunk(1, 2, 3)))
      },
      test("once completes without emission when input ends prematurely") {
        val partial = BitVector.fromInt(0x123456, 24)

        val effect =
          ZStream
            .fromIterable(Chunk(partial.take(10), partial.drop(10)))
            .via(ZStreamDecoder.once(int32Decoder))
            .runCollect

        assertZIO(effect)(Assertion.isEmpty)
      },
      test("many fails fast on unrecoverable errors") {
        val encoded = Chunk(5, -1, 7).map(int8Codec.encode(_).require)
        val stream  = ZStream.fromIterable(split(encoded.reduce(_ ++ _), 4, 4, 4, 4, 4, 4))

        for exit <- stream.via(ZStreamDecoder.many(positiveByteDecoder)).runCollect.exit
        yield exit match
          case Exit.Failure(cause) =>
            val codecFailure = cause.failureOption.collect { case err: CodecError => err }
            assertTrue(codecFailure.isDefined)
          case Exit.Success(_)     => assertTrue(false)
      },
      test("tryMany stops gracefully after an unrecoverable error") {
        val encoded = Chunk(5, -1, 7).map(int8Codec.encode(_).require)
        val chunks  = split(encoded.reduce(_ ++ _), 5, 7, 6, 6)

        val effect =
          ZStream
            .fromIterable(chunks)
            .via(ZStreamDecoder.tryMany(positiveByteDecoder))
            .runCollect

        assertZIO(effect)(Assertion.equalTo(Chunk(5)))
      },
      test("once can emit a value without consuming input for peek decoders") {
        val ascii  = headerBits(0x3031)
        val ebcdic = headerBits(0xf0f1)

        val effect =
          for
            asciiDetected  <-
              ZStream
                .fromIterable(Chunk(ascii))
                .via(ZStreamDecoder.once(charsetPeekDecoder))
                .runCollect
            ebcdicDetected <-
              ZStream
                .fromIterable(Chunk(ebcdic))
                .via(ZStreamDecoder.once(charsetPeekDecoder))
                .runCollect
          yield asciiDetected -> ebcdicDetected

        val expected = (Chunk(StandardCharsets.US_ASCII), Chunk(Ibm037))

        assertZIO(effect)(Assertion.equalTo(expected))
      },
    )

  private def split(bits: BitVector, parts: Int*): Chunk[BitVector] =
    val builder = ChunkBuilder.make[BitVector]()
    var start   = bits
    parts.foreach { size =>
      val (chunk, remainder) = start.splitAt(size.toLong)
      if chunk.nonEmpty then builder += chunk
      start = remainder
    }
    if start.nonEmpty then builder += start
    builder.result()

  private val Ibm037: Charset = Charset.forName("IBM037")

  private val charsetPeekDecoder: Decoder[Charset] =
    new Decoder[Charset]:
      private val EbcdicEncoding = 0xf0f1.toShort
      private val AsciiEncoding  = 0x3031.toShort

      override def decode(bits: BitVector): Attempt[DecodeResult[Charset]] =
        (codecs.ignore(32) ~> codecs.short16).decode(bits).flatMap {
          case DecodeResult(`EbcdicEncoding`, _) =>
            Attempt.successful(DecodeResult(Ibm037, bits))
          case DecodeResult(`AsciiEncoding`, _)  =>
            Attempt.successful(DecodeResult(StandardCharsets.US_ASCII, bits))
          case DecodeResult(other, _)            =>
            Attempt.failure(Err(s"Unexpected marker: 0x${(other & 0xffff).toHexString.toUpperCase}"))
        }

  private def headerBits(marker: Int): BitVector =
    BitVector.fromInt(0x50, 32) ++ BitVector.fromInt(marker, 16)
