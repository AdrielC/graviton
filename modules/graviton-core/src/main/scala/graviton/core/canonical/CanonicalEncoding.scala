package graviton.core.canonical

import graviton.core.keys.{KeyBits, ViewTransform}

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

/**
 * Canonical bytes for any hashed identity.
 *
 * Rule: If bytes are hashed, they MUST be produced here (or by a sub-module of this package).
 * This prevents accidental format drift and “sometimes JSON / sometimes concat” bugs.
 */
object CanonicalEncoding:

  object ViewTransformV1:
    val Version: Byte = 1

    /** Canonical bytes for hashing view identity. */
    def encode(transform: ViewTransform): Array[Byte] =
      // Format:
      //   u8 version = 1
      //   name: u32 len + bytes
      //   scope: u8 present + (u32 len + bytes if present)
      //   args: u32 count + repeated (key, value) each (u32 len + bytes)

      def utf8(s: String): Array[Byte] =
        Option(s).getOrElse("").getBytes(StandardCharsets.UTF_8)

      def sized(bytes: Array[Byte]): Array[Byte] =
        val len = bytes.length
        val bb  = ByteBuffer.allocate(4 + len)
        bb.putInt(len)
        bb.put(bytes)
        bb.array()

      val nameBytes  = sized(utf8(transform.name.value))
      val scopeBytes =
        transform.scope match
          case None        => Array(0.toByte)
          case Some(value) => Array(1.toByte) ++ sized(utf8(value.value))

      val args0     = transform.normalizedArgs
      val argsCount = ByteBuffer.allocate(4).putInt(args0.length).array()
      val argsBytes = args0.flatMap { case (k, v) => sized(utf8(k.value)) ++ sized(utf8(v.value)) }.toArray

      Array(Version) ++ nameBytes ++ scopeBytes ++ argsCount ++ argsBytes

  object KeyBitsV1:
    val Version: Byte = 1

    def encode(bits: KeyBits): Array[Byte] =
      // Format:
      //   u8 version = 1
      //   algoName: u32 len + utf8 bytes
      //   digestBytes: u32 len + bytes
      //   size: i64 big-endian
      val algoBytes   = bits.algo.primaryName.getBytes(StandardCharsets.UTF_8)
      val digestBytes = bits.digest.bytes
      val sizeBytes   = ByteBuffer.allocate(8).putLong(bits.size).array()

      Array(Version) ++
        ByteBuffer.allocate(4).putInt(algoBytes.length).array() ++ algoBytes ++
        ByteBuffer.allocate(4).putInt(digestBytes.length).array() ++ digestBytes ++
        sizeBytes
