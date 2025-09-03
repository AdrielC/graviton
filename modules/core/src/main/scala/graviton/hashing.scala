package graviton

import zio.*
import zio.stream.*
import java.security.MessageDigest
import io.github.rctcwyvrn.blake3.Blake3

/** Helpers for computing hashes over byte streams. Supports both SHA variants
  * (for FIPS deployments) and Blake3. Hashes are exposed via [[Hash]] which
  * captures the algorithm alongside the digest bytes.
  */
object Hashing:

  /** Create an incremental hasher for the chosen algorithm. */
  def hasher(algo: HashAlgorithm): UIO[Hasher] =
    algo match
      case HashAlgorithm.SHA256 => ZIO.succeed(Hasher.messageDigest("SHA-256"))
      case HashAlgorithm.SHA512 => ZIO.succeed(Hasher.messageDigest("SHA-512"))
      case HashAlgorithm.Blake3 => Hasher.blake3

  /** Compute the digest of an entire byte stream. */
  def compute(stream: Bytes, algo: HashAlgorithm): UIO[Chunk[Byte]] =
    for
      h <- hasher(algo)
      _ <- stream.runForeachChunk(h.update).orDie
      d <- h.digest
    yield d

  /** A sink that consumes a byte stream and yields its digest. */
  def sink(
      algo: HashAlgorithm
  ): ZSink[Any, Throwable, Byte, Nothing, Chunk[Byte]] =
    ZSink.collectAll[Byte].mapZIO(bs => compute(ZStream.fromChunk(bs), algo))

  /** Produce a stream of rolling digests for the incoming byte stream. Each
    * emitted [[Hash]] represents the digest of all bytes seen so far. This is a
    * ZIO-port of fs2's `Scan` utility.
    */
  def rolling(
      stream: Bytes,
      algo: HashAlgorithm
  ): ZStream[Any, Throwable, Hash] =
    ZStream.unwrap {
      hasher(algo).map { h =>
        stream.mapChunksZIO { chunk =>
          for
            _ <- h.update(chunk)
            dig <- h.snapshot
          yield Chunk.single(Hash(dig, algo))
        }
      }
    }

/** Abstraction over incremental hashing implementations. */
trait Hasher:
  def update(chunk: Chunk[Byte]): UIO[Unit]
  def snapshot: UIO[Chunk[Byte]]
  def digest: UIO[Chunk[Byte]]

object Hasher:

  /** MessageDigest backed hasher (SHA family). */
  def messageDigest(algo: String): Hasher =
    val md = MessageDigest.getInstance(algo)
    new Hasher:
      def update(chunk: Chunk[Byte]): UIO[Unit] =
        ZIO.attempt(md.update(chunk.toArray)).orDie
      def snapshot: UIO[Chunk[Byte]] =
        ZIO
          .attempt(
            Chunk.fromArray(md.clone().asInstanceOf[MessageDigest].digest())
          )
          .orDie
      def digest: UIO[Chunk[Byte]] =
        ZIO.attempt(Chunk.fromArray(md.digest())).orDie

  /** Pure-Java Blake3 hasher. */
  def blake3: UIO[Hasher] =
    Ref.make(Vector.empty[Chunk[Byte]]).map { ref =>
      new Hasher:
        def update(chunk: Chunk[Byte]): UIO[Unit] = ref.update(_ :+ chunk).unit
        private def compute(parts: Vector[Chunk[Byte]]): Chunk[Byte] =
          val h = Blake3.newInstance()
          parts.foreach(ch => h.update(ch.toArray))
          Chunk.fromArray(h.digest())
        def snapshot: UIO[Chunk[Byte]] = ref.get.map(compute)
        def digest: UIO[Chunk[Byte]] = ref.getAndSet(Vector.empty).map(compute)
    }
