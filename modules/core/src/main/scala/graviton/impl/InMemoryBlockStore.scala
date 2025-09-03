package graviton.impl

import graviton.*
import zio.*
import zio.stream.*
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.all.*

final class InMemoryBlockStore private (
    index: Ref[Set[BlockKey]],
    primary: BlobStore
) extends BlockStore:

  def put: ZSink[Any, Throwable, Byte, Nothing, BlockKey] =
    ZSink.collectAll[Byte].mapZIO { data =>
      val chunk = Chunk.fromIterable(data)
      for
        hashBytes <- Hashing
          .compute(Bytes(ZStream.fromChunk(chunk)), HashAlgorithm.SHA256)
        digest = hashBytes.assume[MinLength[16] & MaxLength[64]]
        sizeRef = chunk.length.assume[Positive]
        key = BlockKey(Hash(digest, HashAlgorithm.SHA256), sizeRef)
        _ <- index.update(_ + key)
        _ <- primary.write(key, Bytes(ZStream.fromChunk(chunk)))
      yield key
    }

  def get(key: BlockKey): IO[Throwable, Option[Bytes]] =
    primary.read(key)

  def has(key: BlockKey): IO[Throwable, Boolean] =
    index.get.map(_.contains(key))

  def delete(key: BlockKey): IO[Throwable, Boolean] =
    for
      existed <- index.modify(s => (s.contains(key), s - key))
      _ <- if existed then primary.delete(key).unit else ZIO.unit
    yield existed

  def list(selector: BlockKeySelector): ZStream[Any, Throwable, BlockKey] =
    ZStream.fromZIO(index.get).flatMap { set =>
      val all = set.toVector
      selector.prefix match
        case None => ZStream.fromIterable(all)
        case Some(p) =>
          ZStream.fromIterable(
            all.filter(_.hash.bytes.take(p.length) == Chunk.fromArray(p))
          )
    }

object InMemoryBlockStore:
  def make(primary: BlobStore): UIO[InMemoryBlockStore] =
    Ref.make(Set.empty[BlockKey]).map(new InMemoryBlockStore(_, primary))
