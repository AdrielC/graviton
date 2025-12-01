package graviton

import zio.*
import zio.stream.*
import zio.prelude.NonEmptySortedMap

/**
 * Simple Merkle tree builder. The stream is split into fixed-size chunks, each
 * leaf hashed individually, then paired up until a single root hash remains.
 * For odd numbers of leaves the last hash is duplicated.
 */
object Merkle:

  def root(
    stream: Bytes,
    chunkSize: Int,
    algo: HashAlgorithm,
  ): UIO[Chunk[Byte]] =
    Hashing.ref.locally(NonEmptySortedMap(algo -> None)):
      for
        bytes  <- stream.runCollect.orDie
        groups  = bytes.grouped(chunkSize).map(Chunk.fromIterable).toList
        hashes <- ZIO.foreach(groups)(b => Hashing.compute(Bytes(ZStream.fromChunk(b))).orDie)
        root   <- build(hashes.map(s => Chunk.fromIterable(s.bytes.values.toChunk.flatMap(b => b))))
      yield root

  private def build(
    level: List[Chunk[Byte]],
  ): UIO[Chunk[Byte]] =
    Hashing.ref.getHashAlgo.flatMap: algo => 
      level match
        case h :: Nil => ZIO.succeed(Chunk.fromIterable(algo.hash(h)))
        case _        =>
          val next = ZIO.foreach(level.grouped(2).toList) {
            case a :: b :: Nil => ZIO.succeed(algo.hash(a ++ b))
            case a :: Nil      => ZIO.succeed(algo.hash(a ++ a))
            case _             => sys.error("unreachable")
          }
          next.map(n => Chunk.fromIterable((n.flatMap(c => Chunk.fromArray(c.toArray)))))
    