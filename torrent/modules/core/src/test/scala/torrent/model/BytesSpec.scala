package torrent
package model

import java.nio.ByteBuffer
import java.util.UUID

import io.github.iltotore.iron.{ autoRefine, refineUnsafe }
import scodec.bits.BitVector

import zio.Chunk
import zio.test.*

import Bytes.*

/**
 * Test suite for the Bytes type
 */
case object BytesSpec extends ZIOSpecDefault {

  def spec = suite("Bytes")(
    test("create from individual bytes"):
      val b: Bytes = Bytes(1, 2, 3)
      assertTrue(
        b.size == 3,
        b(0) == 1.toByte,
        b(1) == 2.toByte,
        b(2) == 3.toByte
      )
    ,
    test("create from array"):
      val arr = Array[Byte](1, 2, 3)
      val b   = Bytes(arr.head, arr.tail*)
      assertTrue(
        b.size == 3,
        b(0) == 1.toByte,
        b(1) == 2.toByte,
        b(2) == 3.toByte
      )
    ,
    test("create from ByteBuffer"):
      val buf = ByteBuffer.allocate(3)
      buf.put(1.toByte).put(2.toByte).put(3.toByte)
      buf.flip()
      val b   = Bytes(buf).toOption.get
      assertTrue(
        b.size == 3,
        b(0) == 1.toByte,
        b(1) == 2.toByte,
        b(2) == 3.toByte
      )
    ,
    test("convert to/from BitVector"):
      val bytes           = Bytes(1, 2, 3)
      val bits: BitVector = bytes.toBitVector
      val back: Bytes     = Bytes.fromBitVector(bits).toOption.get

      assertTrue(bytes == back)
    ,
    test("fromUUID and toUUID"):
      val uuid       = UUID.randomUUID()
      val bytes      = Bytes.fromUUID(uuid)
      val backToUuid = bytes.toUUID.toOption.get

      assertTrue(uuid == backToUuid)
    ,
    test("append bytes"):
      val a: Bytes = Bytes(1, 2)
      val b: Bytes = Bytes(3, 4)
      val c: Bytes = a.appendBytes(b)

      assertTrue(
        c.getLength == Length(4L),
        c(Index(0L)).contains(1.toByte),
        c(Index(1L)).contains(2.toByte),
        c(Index(2L)).contains(3.toByte),
        c(Index(3L)).contains(4.toByte)
      )
    ,
    test("take and drop"):
      val b: Bytes      = Bytes(1, 2, 3, 4, 5)
      val first3: Bytes = b.take(Length(3L))
      val last2: Bytes  = b.drop(Length(3L)).get

      assertTrue(
        first3.size == 3,
        first3.get(Index(0L)).contains(1.toByte),
        first3(Index(1L)).contains(2.toByte),
        first3(Index(2L)).contains(3.toByte),
        last2.size == 2,
        last2(Index(0L)).contains(4.toByte),
        last2(Index(1L)).contains(5.toByte)
      )
    ,
    test("numeric conversion"):
      val int       = 0x01020304
      val bytes     = Bytes.fromInt(int)
      val backToInt = bytes.toInt().toOption.get
      assertTrue(int == backToInt)
    ,
    test("hex encoding/decoding"):
      val bytes   = Bytes(0xde.toByte, 0xad.toByte, 0xbe.toByte, 0xef.toByte)
      val hex     = bytes.toHex
      val fromHex = Bytes.fromValidHex(hex).toOption

      assertTrue((hex.contains("deadbeef")) && (fromHex contains bytes))
    ,
    test("string encoding/decoding"):
      val text    = "Hello, world!"
      val bytes   = Bytes.encodeUtf8(text).toOption.get
      val decoded = bytes.decodeUtf8.getOrElse("")

      assertTrue(text == decoded)
    ,
    test("bitwise operations"):
      val a = Bytes(0x0f.toByte, 0x00.toByte)
      val b = Bytes(0xf0.toByte, 0x0f.toByte)

      val orResult  = a | b
      val andResult = a & b
      val xorResult = a ^ b
      val notResult = ~a

      assertTrue(
        orResult.toHex == "ff0f",
        andResult.toHex == "0000",
        xorResult.toHex == "ff0f",
        notResult.toHex == "f0ff"
      )
    ,
    test("binary string representation"):
      val bytes  = Bytes(0xa5.toByte) // 10100101
      val binary = bytes.toBin

      assertTrue(binary == "10100101")
    ,
    test("stream conversion"):
      val bytes       = Bytes(1, 2, 3)
      val stream      = bytes.toZStream
      val backToChunk = zio.Unsafe.unsafe { implicit unsafe =>
        zio.Runtime.default.unsafe
          .run(
            stream.runCollect
          )
          .getOrThrow()
      }

      assertTrue(backToChunk == Chunk(1.toByte, 2.toByte, 3.toByte))
    ,
    test("update at index"):

      val original = Bytes(1, 2, 3)

      val modified = original.update(Index(1L), 42.toByte)

      assertTrue(
        modified.isDefined,
        modified.get.size == 3,
        modified.get(Index(0L)).contains(1.toByte),
        modified.get(Index(1L)).contains(42.toByte),
        modified.get(Index(2L)).contains(3.toByte)
      )
    ,
    test("compare bytes"):
      val a = Bytes(1, 2, 3)
      val b = Bytes(1, 2, 4)
      val c = Bytes(1, 2, 3)

      assertTrue(
        a.compare(b) < 0,
        b.compare(a) > 0,
        a.compare(c) == 0
      )
  )
}
