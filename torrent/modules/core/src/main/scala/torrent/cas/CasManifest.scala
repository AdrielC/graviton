package torrent
package cas

import java.util.concurrent.atomic.AtomicLong

import torrent.BinaryKey.Hash
import torrent.{ BinaryKey, HashAlgo, Length }

import zio.*
import zio.schema.*
import zio.schema.annotation.description

/**
 * A chunk in content-addressable storage
 */
case class CasChunk(
  key:       BinaryKey,
  length:    Length,
  hash:      Hash,
  algorithm: HashAlgo
) derives Schema

/**
 * Manifest for reassembling content from CAS chunks
 */
case class CasManifest(
  chunks:        NonEmptyChunk[CasChunk],
  totalLength:   Length,
  contentHash:   Hash,
  algorithm:     HashAlgo,
  recompression: Option[RecompressionInfo] = None
) derives Schema:
  /**
   * Get all chunk keys in order
   */
  def chunkKeys: NonEmptyChunk[BinaryKey] = chunks.map(_.key)

  /**
   * Verify manifest integrity
   */
  def verify: Boolean =
    chunks.map(_.length.toLong).sum == totalLength.toLong

object CasManifest:

  /**
   * Create manifest from chunks
   */
  def fromChunks(
    chunks:      NonEmptyChunk[CasChunk],
    contentHash: Hash,
    algorithm:   HashAlgo
  ): Either[String, CasManifest] =
    val totalLength = chunks.map(_.length.toLong).sum
    Length.either(totalLength).map { len =>
      CasManifest(chunks, len, contentHash, algorithm)
    }

/**
 * Information about recompression applied to content
 */
case class RecompressionInfo(
  originalFilters:    List[String],
  appliedCompression: Option[String] = None,
  compressionRatio:   Option[Double] = None
) derives Schema

/**
 * Builder for creating CAS manifests during chunking
 */
class CasManifestBuilder(algorithm: HashAlgo = HashAlgo.Blake3):
  private val chunks: ChunkBuilder[CasChunk] = ChunkBuilder.make[CasChunk]()
  private val totalBytes: AtomicLong         = AtomicLong(0L)

  /**
   * Add a chunk to the manifest
   */
  def addChunk(data: Bytes, key: BinaryKey.Owned): UIO[Unit] =
    for {
      hash  <- ZIO
                 .attempt(Hash.applyUnsafe(algorithm.hash(data.toArray)))
                 .orElse(ZIO.succeed(Hash.applyUnsafe("0" * 43)))
      length = data.getLength
      chunk  = CasChunk(key, length, hash, algorithm)
    } yield
      chunks += chunk
      totalBytes.updateAndGet(_ + data.length): Unit

  /**
   * Build the final manifest
   */
  def build(contentHash: Hash): IO[String, CasManifest] =
    for {
      totalLength   <- ZIO.fromEither(Length.either(totalBytes.get))
      nonEmptyChunk <- ZIO.fromOption(NonEmptyChunk.fromChunk(chunks.result())).mapError(_ => "empty chunk")
      manifest       = CasManifest(nonEmptyChunk, totalLength, contentHash, algorithm)
    } yield manifest

  /**
   * Reset the builder
   */
  def reset: UIO[Unit] = ZIO.succeed:
    chunks.clear
    totalBytes.set(0L)

object CasManifestBuilder:
  /**
   * Create a new manifest builder
   */
  def apply(algorithm: HashAlgo = HashAlgo.Blake3.asInstanceOf[HashAlgo]): CasManifestBuilder =
    new CasManifestBuilder(algorithm)

/**
 * Compute hash of chunk data using Apache Commons Codec
 */
private[torrent] def computeHash(data: Chunk[Byte], algorithm: HashAlgo): Hash =
  Hash.applyUnsafe(algorithm.hash(data.toArray))
