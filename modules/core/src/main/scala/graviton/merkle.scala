package graviton

import zio.*
import zio.stream.*

/**
 * Simple Merkle tree builder. The stream is split into fixed-size chunks,
 * each leaf hashed individually, then paired up until a single root hash
 * remains. For odd numbers of leaves the last hash is duplicated.
 */
object Merkle:

  def root(stream: Bytes, chunkSize: Int, algo: HashAlgorithm): UIO[Chunk[Byte]] =
    for
      bytes <- stream.runCollect.orDie
      groups = bytes.grouped(chunkSize).map(Chunk.fromIterable).toList
      hashes <- ZIO.foreach(groups)(b => Hashing.compute(ZStream.fromChunk(b), algo))
      root   <- build(hashes, algo)
    yield root

  private def build(level: List[Chunk[Byte]], algo: HashAlgorithm): UIO[Chunk[Byte]] =
    level match
      case h :: Nil => ZIO.succeed(h)
      case _ =>
        val next = level.grouped(2).toList.map {
          case a :: b :: Nil =>
            Hashing.compute(ZStream.fromChunk(a ++ b), algo)
          case a :: Nil =>
            // duplicate last hash when odd number of nodes
            Hashing.compute(ZStream.fromChunk(a ++ a), algo)
          case _ => ZIO.dieMessage("unreachable")
        }
        ZIO.collectAll(next).flatMap(build(_, algo))

