package graviton

import zio.*
import zio.stream.*
import java.security.MessageDigest
import io.github.rctcwyvrn.blake3.Blake3
import graviton.GravitonError.ChunkerFailure
import graviton.core.model.Block

import graviton.domain.HashBytes
import fs2.Chunk.ByteBuffer
import scodec.bits.ByteVector

/**
 * Helpers for computing hashes over byte streams. Supports both SHA variants
 * (for FIPS deployments) and Blake3. Hashes are exposed via [[Hash]] which
 * captures the algorithm alongside the digest bytes.
 */
object Hashing:

  /** Create an incremental hasher for the chosen algorithm. */
  def hasher(algo: HashAlgorithm): UIO[Hasher] =
    algo match
      case HashAlgorithm.SHA256 => ZIO.succeed(Hasher.messageDigest("SHA-256", HashAlgorithm.SHA256))
      case HashAlgorithm.SHA512 => ZIO.succeed(Hasher.messageDigest("SHA-512", HashAlgorithm.SHA512))
      case HashAlgorithm.Blake3 => Hasher.blake3

  /** Compute the digest of an entire byte stream. */
  def compute(stream: Bytes, algo: HashAlgorithm): UIO[Chunk[Byte]] =
    for
      h <- hasher(algo)
      _ <- stream.runForeachChunk((chunk: Chunk[Byte]) => h.update(chunk)).orDie
      d <- h.digest
    yield d

  /** A sink that consumes a byte stream and yields its digest. */
  def sink(
    algo: HashAlgorithm
  ): ZSink[Any, ChunkerFailure, Byte, Nothing, Hash] =
    ZSink.unwrap {
      hasher(algo).map { h =>
        ZSink
          .foreachChunk[Any, Nothing, Byte](h.update)
          .mapZIO(_ => h.digest.map(Hash(_, algo)))
      }
    }

  /**
   * Produce a stream of rolling digests for the incoming byte stream. Each
   * emitted [[Hash]] represents the digest of all bytes seen so far. This is a
   * ZIO-port of fs2's `Scan` utility.
   */
  def rolling(
    stream: Blocks,
    algo: HashAlgorithm,
  ): ZStream[Any, Throwable, Hash] =
    ZStream.unwrap {
      hasher(algo).map { h =>
        stream.mapZIO { (block: Block) =>
          for
            _   <- h.update(block.bytes.toChunk)
            dig <- h.snapshot
          yield Hash(dig, algo)
        }
      }
    }

/** Abstraction over incremental hashing implementations. */
trait Hasher:
  self =>

  import Hasher.Hashable
  
  def algorithm: HashAlgorithm
  def update(chunk: Hashable): UIO[Unit]
  def snapshot: UIO[HashBytes]
  def digest: UIO[HashBytes]

object Hasher:

  type Hashable = Array[Byte] | Chunk[Byte] | Byte | ByteBuffer | ByteVector

  /** MessageDigest backed hasher (SHA family). */
  def messageDigest(jcaName: String, algo: HashAlgorithm): Hasher =
    val md = ThreadLocal.withInitial(() => MessageDigest.getInstance(jcaName))
    new Hasher:
      def algorithm: HashAlgorithm              = algo
      def update(chunk: Hashable): UIO[Unit] =
        chunk match
          case array: Array[Byte] => ZIO.attempt(md.get().update(array)).orDie
          case chunk: Chunk[Byte] => ZIO.attempt(md.get().update(chunk.toArray)).orDie
          case byte: Byte => ZIO.attempt(md.get().update(Array(byte))).orDie
          case byteBuffer: ByteBuffer => ZIO.attempt(md.get().update(byteBuffer.toArray)).orDie
          case byteVector: ByteVector => ZIO.attempt(md.get().update(byteVector.toArray)).orDie
      def snapshot: UIO[HashBytes]            =
        ZIO.attempt {
          val cloned = md.get().clone().asInstanceOf[MessageDigest]
          HashBytes.applyUnsafe(Chunk.fromArray(cloned.digest()))
        }.orDie <* ZIO.yieldNow
      def digest: UIO[HashBytes]              =
        ZIO.attempt(HashBytes.applyUnsafe(  Chunk.fromArray(md.get().digest()))).orDie

  /** Pure-Java Blake3 hasher. */
  def blake3: UIO[Hasher] = 
    ZIO.succeed {
      val blake3 = Blake3.newInstance()
      new Hasher:
        def algorithm: HashAlgorithm                                 = HashAlgorithm.Blake3
        def update(chunk: Hashable): UIO[Unit]                     = chunk match
          case array: Array[Byte] => ZIO.attempt(blake3.update(array)).orDie
          case chunk: Chunk[Byte] => ZIO.attempt(blake3.update(chunk.toArray)).orDie
          case byte: Byte => ZIO.attempt(blake3.update(Array(byte))).orDie
          case byteBuffer: ByteBuffer => ZIO.attempt(blake3.update(byteBuffer.toArray)).orDie
          case byteVector: ByteVector => ZIO.attempt(blake3.update(byteVector.toArray)).orDie
        def snapshot: UIO[HashBytes]                               = ZIO.attempt(HashBytes.applyUnsafe(Chunk.fromArray(blake3.digest()))).orDie
        def digest: UIO[HashBytes]                                 = ZIO.attempt(HashBytes.applyUnsafe(Chunk.fromArray(blake3.digest()))).orDie
    }
