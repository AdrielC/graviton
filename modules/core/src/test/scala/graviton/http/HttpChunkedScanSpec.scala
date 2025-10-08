package graviton.http

import zio.*
import zio.stream.*
import zio.test.*
import zio.test.Assertion.*

import scala.util.Try

object HttpChunkedScanSpec extends ZIOSpecDefault:

  private def decode(bytes: Chunk[Byte]): Chunk[Chunk[Byte]] =
    val (_, out) = HttpChunkedScan.chunkedDecode.runAll(bytes)
    out

  override def spec: Spec[TestEnvironment, Any] =
    suite("HttpChunkedScan")(
      test("decodes multiple chunks and final terminator") {
        val input  = Chunk.fromArray("4\r\nWiki\r\n5\r\npedia\r\n0\r\n\r\n".getBytes("US-ASCII"))
        val output = decode(input).map(bytes => new String(bytes.toArray, "US-ASCII"))
        assert(output)(equalTo(Chunk("Wiki", "pedia")))
      },
      test("handles chunk extensions and ignores trailers") {
        val payload = "A;foo=bar\r\nHelloWorld\r\n0\r\nSome: header\r\n\r\n"
        val input   = Chunk.fromArray(payload.getBytes("US-ASCII"))
        val output  = decode(input).map(bytes => new String(bytes.toArray, "US-ASCII"))
        assert(output)(equalTo(Chunk("HelloWorld")))
      },
      test("pipeline view flattens to raw bytes") {
        val payload = Chunk.fromArray("3\r\nabc\r\n0\r\n\r\n".getBytes("US-ASCII"))
        val stream  = ZStream.fromChunk(payload)
        val program = stream.via(HttpChunkedScan.chunkedPipeline).runCollect
        assertZIO(program.map(bytes => new String(bytes.toArray, "US-ASCII")))(equalTo("abc"))
      },
      test("fails on malformed chunk without CRLF") {
        val broken = Chunk.fromArray("1\r\nA0\r\n\r\n".getBytes("US-ASCII"))
        val result = Try(HttpChunkedScan.chunkedDecode.runAll(broken))
        assert(result.isFailure)(isTrue)
      },
      test("fails on premature eof inside body") {
        val broken = Chunk.fromArray("2\r\nA".getBytes("US-ASCII"))
        val result = Try(HttpChunkedScan.chunkedDecode.runAll(broken))
        assert(result.isFailure)(isTrue)
      },
    )
