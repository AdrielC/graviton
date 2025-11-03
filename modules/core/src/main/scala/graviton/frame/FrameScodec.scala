package graviton.frame

import graviton.*
import scodec.*
import scodec.bits.*
import scodec.codecs.*
// import scodec.interop.cats.given
// import zio.Chunk
// import cats.syntax.all.*
// import cats.implicits.*
import Codec.*

object FrameScodec:

  private val magic: Codec[Unit] =
    constant(BitVector.encodeAscii("QUASAR").toOption.get).as[Unit]

  private val version: Codec[Int] = uint8

  private val flags: Codec[Int]         = uint8
  private val hashId: Codec[Int]        = uint8
  private val compId: Codec[Int]        = uint8
  private val encId: Codec[Int]         = uint8
  private val truncLen: Codec[Int]      = uint8
  private val payloadLen: Codec[BigInt] = uint64

  // private val variable8: Codec[ByteVector]  = variableSizeBytes(uint8, bytes)
  // private val variable16: Codec[ByteVector] = variableSizeBytes(uint16, bytes)

  // private def chunkToBv(c: Chunk[Byte]): ByteVector  = ByteVector.view(c.toArray)
  // private def bvToChunk(bv: ByteVector): Chunk[Byte] = Chunk.fromArray(bv.toArray)

  val codec: Codec[(Unit, Int, Int, Int, Int, Int, Int, BigInt)] =
    (magic :: version :: flags :: hashId :: compId :: encId :: truncLen :: payloadLen.tuple)

  val headerCodec: Decoder[FrameHeader] = ???
  // (magic: Decoder[Unit], version: Decoder[Int], flags: Decoder[Int], hashId: Decoder[Int], compId: Decoder[Int], encId: Decoder[Int], truncLen: Decoder[Int], payloadLen: Decoder[BigInt])
  // .mapN(
  //   { case (_, ver, fl, hid, cid, eid, tl, plen) => FrameHeader(
  //     version = ver.toByte,
  //     flags = fl.toByte,
  //     hashId = hid.toByte,
  //     compId = cid.toByte,
  //     encId = eid.toByte,
  //     truncHashLen = tl.toByte,
  //     payloadLen = plen.toLong,
  //   )
  // })

  // val frameCodec: Codec[Frame] =
  //   (headerCodec, bytes).mapN(
  //         (h, payload) => Frame(h, bvToChunk(payload)),
  //       ).contramap[Frame] {
  //         case Frame(h, payload) => (h, chunkToBv(payload))
  //       }
