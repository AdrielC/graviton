package graviton.impl

import graviton.*
import zio.*
import zio.stream.*
import zio.ChunkBuilder
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.all.*

final class InMemoryBlockStore private (
    index: Ref[Set[BlockKey]],
    stores: Map[BlobStoreId, BlobStore],
    primary: BlobStore,
    resolver: BlockResolver
) extends BlockStore:

  def put: ZSink[Any, Throwable, Byte, Nothing, BlockKey] =
    val collect =
      ZSink
        .foldLeftChunks[Byte, ChunkBuilder[Byte]](ChunkBuilder.make[Byte]()) {
          (b, ch) =>
            b ++= ch
        }
        .map(_.result())
    collect
      .zipWithPar(Hashing.sink(HashAlgorithm.SHA256))((chunk, dig) =>
        (chunk, dig)
      )
      .mapZIO { (chunk, hashBytes) =>
        val digest = hashBytes.assume[MinLength[16] & MaxLength[64]]
        val sizeRef = chunk.length.assume[Positive]
        val key = BlockKey(Hash(digest, HashAlgorithm.SHA256), sizeRef)
        for
          exists <- index.modify { s =>
            val exists = s.contains(key)
            val updated = if exists then s else s + key
            (exists, updated)
          }
          _ <-
            if exists then ZIO.unit
            else
              (for
                _ <- primary.write(key, Bytes(ZStream.fromChunk(chunk)))
                status <- primary.status
                _ <- resolver.record(key, BlockSector(primary.id, status))
              yield ()
            )
        yield key
      }

  def get(key: BlockKey): IO[Throwable, Option[Bytes]] =
    resolver.resolve(key).flatMap { sectors =>
      def loop(rem: Chunk[BlockSector]): IO[Throwable, Option[Bytes]] =
        rem.headOption match
          case None => ZIO.succeed(None)
          case Some(sec) =>
            stores.get(sec.blobStoreId) match
              case None => loop(rem.drop(1))
              case Some(store) =>
                store.read(key).flatMap {
                  case None => loop(rem.drop(1))
                  case s    => ZIO.succeed(s)
                }
      loop(sectors)
    }

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
  def make(
      primary: BlobStore,
      resolver: BlockResolver,
      others: Seq[BlobStore] = Seq.empty
  ): UIO[InMemoryBlockStore] =
    Ref.make(Set.empty[BlockKey]).map { ref =>
      val all = (primary +: others).map(bs => bs.id -> bs).toMap
      new InMemoryBlockStore(ref, all, primary, resolver)
    }
