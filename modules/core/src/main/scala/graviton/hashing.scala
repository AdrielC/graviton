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
  def hasher(algo: HashAlgorithm): ZIO[Scope, Throwable, Hasher] =
    algo match
      case HashAlgorithm.SHA256 => Hasher.messageDigest(HashAlgorithm.SHA256)
      case HashAlgorithm.SHA512 => Hasher.messageDigest(HashAlgorithm.SHA512)
      case HashAlgorithm.Blake3 => Hasher.blake3

  /** Compute the digest of an entire byte stream. */
  def compute(stream: Bytes, algo: HashAlgorithm): UIO[HashBytes] =
    ZIO.scoped:
      for
        h <- hasher(algo).orDie
        _ <- stream.runForeachChunk((chunk: Chunk[Byte]) => h.update(chunk)).orDie
        d <- h.digest
      yield d

  /** A sink that consumes a byte stream and yields its digest. */
  def sink(
    algo: HashAlgorithm
  ): ZSink[Any, ChunkerFailure, Byte, Nothing, Hash] =
    ZSink.unwrapScoped {
      hasher(algo).orDie.map { h =>
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
    ZStream.unwrapScoped {
      hasher(algo).orDie.map { h =>
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
  def messageDigest(algo: HashAlgorithm): ZIO[Scope, Throwable, Hasher] =
    for {

      pool <- ZKeyedPool.make(
                (algo: HashAlgorithm) =>
                  ZIO
                    .log(s"Creating pool for ${algo.canonicalName}")
                    .zipRight(ZIO.attempt(algo match {
                      case HashAlgorithm.SHA256 => MessageDigest.getInstance("SHA-256")
                      case HashAlgorithm.SHA512 => MessageDigest.getInstance("SHA-512")
                      case HashAlgorithm.Blake3 => Blake3MessageDigest()
                    })),
                _ => 2 to 20,
                _ => 20.hours,
              )

      out <- ZIO.scopeWith[Any, Throwable, Hasher] { scope =>
               ZIO.succeed:

                 new Hasher {
                   def algorithm: HashAlgorithm           = algo
                   def update(chunk: Hashable): UIO[Unit] =
                     chunk match
                       case array: Array[Byte]     => scope.extend(pool.get(algo).map(_.update(array)).orDie)
                       case chunk: Chunk[Byte]     => scope.extend(pool.get(algo).map(_.update(chunk.toArray)).orDie)
                       case byte: Byte             => scope.extend(pool.get(algo).map(_.update(Array(byte))).orDie)
                       case byteBuffer: ByteBuffer => scope.extend(pool.get(algo).map(_.update(byteBuffer.toArray)).orDie)
                       case byteVector: ByteVector => scope.extend(pool.get(algo).map(_.update(byteVector.toArray)).orDie)
                   def snapshot: UIO[HashBytes]           =
                     scope.extend(
                       pool
                         .get(algo)
                         .map(md => HashBytes.applyUnsafe(Chunk.fromArray(md.clone().asInstanceOf[MessageDigest].digest())))
                         .orDie
                     )
                   def digest: UIO[HashBytes]             =
                     scope.extend(pool.get(algo).map(md => HashBytes.applyUnsafe(Chunk.fromArray(md.digest()))).orDie)
                 }
             }
    } yield out

  /** Pure-Java Blake3 hasher. */
  def blake3: ZIO[Scope, Throwable, Hasher] =
    for
      blake3 <- ZPool.make(ZIO.attempt(Blake3.newInstance()), size = 10)
      hasher <- ZIO.scopeWith[Any, Throwable, Hasher] { scope =>
                  ZIO.succeed(new Hasher:
                    def algorithm: HashAlgorithm           = HashAlgorithm.Blake3
                    def update(chunk: Hashable): UIO[Unit] = chunk match
                      case array: Array[Byte]     => scope.extend(blake3.get.map(_.update(array)).orDie)
                      case chunk: Chunk[Byte]     => scope.extend(blake3.get.map(_.update(chunk.toArray)).orDie)
                      case byte: Byte             => scope.extend(blake3.get.map(_.update(Array(byte))).orDie)
                      case byteBuffer: ByteBuffer => scope.extend(blake3.get.map(_.update(byteBuffer.toArray)).orDie)
                      case byteVector: ByteVector => scope.extend(blake3.get.map(_.update(byteVector.toArray)).orDie)
                    def snapshot: UIO[HashBytes]           =
                      scope.extend(blake3.get.map(bl => HashBytes.applyUnsafe(Chunk.fromArray(bl.digest())))).orDie
                    def digest: UIO[HashBytes]             =
                      scope.extend(blake3.get.map(bl => HashBytes.applyUnsafe(Chunk.fromArray(bl.digest())))).orDie)
                }
    yield hasher
