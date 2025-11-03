package graviton.frame

import graviton.*
import zio.Chunk

import graviton.core.model.{BlockSize, Block}

/**
 * Self-describing frame format for emitted blocks.
 *
 * Layout (big-endian):
 * - magic:        6 bytes  ("QUASAR")
 * - version:      1 byte   (currently 1)
 * - flags:        1 byte   (reserved)
 * - hashId:       1 byte   (hash algorithm id)
 * - compId:       1 byte   (compression algorithm id)
 * - encId:        1 byte   (encryption algorithm id)
 * - truncHashLen: 1 byte   (length in bytes of truncated hash)
 * - payloadLen:   8 bytes  (unsigned length)
 * - nonceLen:     1 byte   (0 means absent)
 * - nonce:        N bytes
 * - keyIdLen:     1 byte   (0 means absent)
 * - keyId:        K bytes
 * - aadLen:       2 bytes  (0..65535)
 * - aad:          A bytes
 * - truncHash:    T bytes  (payload hash truncated)
 * - payload:      P bytes  (the frame payload)
 */
final case class FrameHeader(
  version: Byte,
  flags: Byte,
  hashId: Byte,
  compId: Byte,
  encId: Byte,
  truncHashLen: Byte,
  payloadLen: BlockSize,
  nonce: Option[Chunk[Byte]] = None,
  keyId: Option[Chunk[Byte]] = None,
  aad: Chunk[Byte] = Chunk.empty,
  truncHash: Chunk[Byte] = Chunk.empty,
)

final case class Frame(header: FrameHeader, payload: Frame.Bytes)

object Frame:
  opaque type Bytes <: Chunk[Byte] = Chunk[Byte]
  object Bytes:
    def apply(bytes: Array[Byte]): Bytes = Chunk.fromArray(bytes)
    def fromChunk(chunk: Chunk[Byte]): Bytes = chunk
    extension (bytes: Bytes)
      def toChunk: Chunk[Byte] = bytes
      // def length: BlockSize = BlockSize.applyUnsafe(bytes.length)
      // def isEmpty: Boolean = bytes.isEmpty

object Algorithms:
  object HashIds:
    val Sha256: Byte = 1
    val Sha512: Byte = 2
    val Blake3: Byte = 3

    def fromAlgo(a: HashAlgorithm): Byte = a match
      case HashAlgorithm.SHA256 => Sha256
      case HashAlgorithm.SHA512 => Sha512
      case HashAlgorithm.Blake3 => Blake3

  object CompressionIds:
    val None: Byte = 0

  object EncryptionIds:
    val None: Byte = 0

object FrameCodec:
  private val Magic: Chunk[Byte] = Chunk.fromArray("QUASAR".getBytes("US-ASCII"))

  def encode(header: FrameHeader, payload: Block): Frame =
    val nonce     = header.nonce.getOrElse(Chunk.empty)
    val keyId     = header.keyId.getOrElse(Chunk.empty)
    val aad       = header.aad
    val truncHash = header.truncHash

    val capacity =
      Magic.length + 1 + 1 + 1 + 1 + 1 + 1 + 8 + 1 + nonce.length + 1 + keyId.length + 2 + aad.length + truncHash.length + payload.length

    val arr                           = new Array[Byte](capacity)
    var p                             = 0
    def put(b: Byte): Unit            = { arr(p) = b; p += 1 }
    def putAll(bs: Chunk[Byte]): Unit = { val _ = bs.copyToArray(arr, p); p += bs.length }
    def putShort(s: Int): Unit        = { arr(p) = ((s >>> 8) & 0xff).toByte; arr(p + 1) = (s & 0xff).toByte; p += 2 }
    def putLong(l: Long): Unit        =
      var i = 7
      while i >= 0 do
        arr(p + (7 - i)) = ((l >>> (i * 8)) & 0xffL).toByte
        i -= 1
      p += 8

    putAll(Magic)
    put(header.version)
    put(header.flags)
    put(header.hashId)
    put(header.compId)
    put(header.encId)
    put(header.truncHashLen)
    putLong(header.payloadLen)
    put(nonce.length.toByte)
    putAll(nonce)
    put(keyId.length.toByte)
    putAll(keyId)
    putShort(aad.length)
    putAll(aad)
    putAll(truncHash)
    putAll(payload)
    Frame(header, Frame.Bytes(arr))
