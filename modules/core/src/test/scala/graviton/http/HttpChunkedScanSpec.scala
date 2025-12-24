package graviton.http

import zio.*
import zio.stream.*
import zio.test.*
import zio.test.Assertion.*
import zio.ChunkBuilder
import graviton.core.scan.FS.*

object HttpChunkedScanSpec extends ZIOSpecDefault:

  private def decode(bytes: Chunk[Byte]): Either[Throwable, Chunk[Chunk[Byte]]] =
    val takes = HttpChunkedScan.chunkedDecode.runChunk(bytes.toArray.toIndexedSeq)
    takes
      .foldLeft[Either[Throwable, ChunkBuilder[Chunk[Byte]]]](Right(ChunkBuilder.make[Chunk[Byte]]())) { (acc, take) =>
        acc.flatMap { builder =>
          take match
            case t: Take[Throwable, Chunk[Byte]] @unchecked =>
              t.exit match
                case Exit.Success(values) =>
                  values.foreach(builder += _)
                  Right(builder)
                case Exit.Failure(cause)  =>
                  cause.failureOption.flatten match
                    case Some(err) => Left(err)
                    case None      => Right(builder)
        }
      }
      .map(_.result())

  override def spec: Spec[TestEnvironment, Any] =
    suite("HttpChunkedScan")(
      test("decodes multiple chunks and final terminator") {
        val input  = Chunk.fromArray("4\r\nWiki\r\n5\r\npedia\r\n0\r\n\r\n".getBytes("US-ASCII"))
        val output = decode(input).map(_.map(bytes => new String(bytes.toArray, "US-ASCII")))
        assert(output)(isRight(equalTo(Chunk("Wiki", "pedia"))))
      },
      test("handles chunk extensions and ignores trailers") {
        val payload = "A;foo=bar\r\nHelloWorld\r\n0\r\nSome: header\r\n\r\n"
        val input   = Chunk.fromArray(payload.getBytes("US-ASCII"))
        val output  = decode(input).map(_.map(bytes => new String(bytes.toArray, "US-ASCII")))
        assert(output)(isRight(equalTo(Chunk("HelloWorld"))))
      },
      test("pipeline view flattens to raw bytes") {
        val payload = Chunk.fromArray("3\r\nabc\r\n0\r\n\r\n".getBytes("US-ASCII"))
        val stream  = ZStream.fromIterable(payload)
        val program = stream.via(HttpChunkedScan.chunkedPipeline).runCollect
        assertZIO(program.map(bytes => new String(bytes.toArray, "US-ASCII")))(equalTo("abc"))
      },
      test("fails on malformed chunk without CRLF") {
        val broken = Chunk.fromArray("1\r\nA0\r\n\r\n".getBytes("US-ASCII"))
        val result = decode(broken)
        assert(result)(isLeft(hasMessage(containsString("Missing CR after chunk body"))))
      },
      test("fails on premature eof inside body") {
        val broken = Chunk.fromArray("2\r\nA".getBytes("US-ASCII"))
        val result = decode(broken)
        assert(result)(isLeft(hasMessage(containsString("Unexpected EOF inside chunk body"))))
      },
      test("pipeline fails with informative error") {
        val payload = Chunk.fromArray("1\r\nA0\r\n\r\n".getBytes("US-ASCII"))
        val stream  = ZStream.fromChunk(payload)
        val program = stream.via(HttpChunkedScan.chunkedPipeline).runCollect
        assertZIO(program.exit)(fails(hasMessage(containsString("Missing CR after chunk body"))))
      },
    )
