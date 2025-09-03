package graviton.impl

import graviton.*
import zio.*
import zio.stream.*
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.all.*

final class InMemoryBlockStore private (
    index: Ref[Set[BlockKey]],
    stores: Map[BlobStoreId, BlobStore],
    primary: BlobStore,
    resolver: BlockResolver
) extends BlockStore:

  def put: ZSink[Any, Throwable, Byte, Nothing, BlockKey] =
    ZSink.unwrap {
      for
        chunks <- Ref.make(Vector.empty[Chunk[Byte]])
        sizeRef <- Ref.make(0)
      yield
        val collect =
          ZSink.foreachChunk[Any, Nothing, Byte] { ch =>
            chunks.update(_ :+ ch) *> sizeRef.update(_ + ch.length)
          }
        collect
          .zipWithPar(Hashing.sink(HashAlgorithm.SHA256))((_, dig) => dig)
          .mapZIO { hashBytes =>
            val digest = hashBytes.assume[MinLength[16] & MaxLength[64]]
            for
              size <- sizeRef.get
              key = BlockKey(
                Hash(digest, HashAlgorithm.SHA256),
                size.assume[Positive]
              )
              exists <- index.get.map(_.contains(key))
              _ <-
                if exists then ZIO.unit
                else
                  (for
                    data <- chunks.get.map(chs => Bytes(ZStream.fromChunks(chs*)))
                    _ <- primary.write(key, data)
                    status <- primary.status
                    _ <- resolver.record(key, BlockSector(primary.id, status))
                    _ <- index.update(_ + key)
                  yield ())
            yield key
          }
    }

  def get(
      key: BlockKey,
      range: Option[ByteRange] = None
  ): IO[Throwable, Option[Bytes]] =
    resolver.resolve(key).flatMap { sectors =>
      def loop(rem: Chunk[BlockSector]): IO[Throwable, Option[Bytes]] =
        rem.headOption match
          case None => ZIO.succeed(None)
          case Some(sec) =>
            stores.get(sec.blobStoreId) match
              case None => loop(rem.drop(1))
              case Some(store) =>
                store.read(key, range).flatMap {
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
