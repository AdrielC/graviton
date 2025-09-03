package graviton.impl

import graviton.*
import zio.*
import zio.stream.*
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.all.*
import java.time.Instant

private final case class BlockState(refs: Int, unreferencedAt: Option[Instant])

final class InMemoryBlockStore private (
    index: Ref[Map[BlockKey, BlockState]],
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
              stateOpt <- index.get.map(_.get(key))
              _ <- stateOpt match
                case Some(state) =>
                  index.update(
                    _.updated(
                      key,
                      state.copy(refs = state.refs + 1, unreferencedAt = None)
                    )
                  )
                case None =>
                  for
                    data <- chunks.get
                      .map(chs => Bytes(ZStream.fromChunks(chs*)))
                    _ <- primary.write(key, data)
                    status <- primary.status
                    _ <- resolver.record(key, BlockSector(primary.id, status))
                    _ <- index.update(_ + (key -> BlockState(1, None)))
                  yield ()
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
    index.get.map(_.get(key).exists(_.refs > 0))

  def delete(key: BlockKey): IO[Throwable, Boolean] =
    Clock.instant.flatMap { now =>
      index.modify { m =>
        m.get(key) match
          case Some(state) if state.refs > 0 =>
            val updated =
              if state.refs == 1 then
                state.copy(refs = 0, unreferencedAt = Some(now))
              else state.copy(refs = state.refs - 1, unreferencedAt = None)
            (true, m.updated(key, updated))
          case _ => (false, m)
      }
    }

  def list(selector: BlockKeySelector): ZStream[Any, Throwable, BlockKey] =
    ZStream.fromZIO(index.get).flatMap { set =>
      val all = set.collect { case (k, st) if st.refs > 0 => k }.toVector
      selector.prefix match
        case None => ZStream.fromIterable(all)
        case Some(p) =>
          ZStream.fromIterable(
            all.filter(_.hash.bytes.take(p.length) == Chunk.fromArray(p))
          )
    }

  def gc(config: GcConfig): UIO[Int] =
    for
      now <- Clock.instant
      toDelete <- index.modify { m =>
        val (del, keep) = m.partition { case (_, st) =>
          st.refs == 0 && st.unreferencedAt.exists { ts =>
            val cutoff =
              ts.plus(java.time.Duration.ofNanos(config.retention.toNanos))
            cutoff.isBefore(now) || cutoff.equals(now)
          }
        }
        (del.keySet, keep)
      }
      _ <- ZIO.foreachDiscard(toDelete)(k => primary.delete(k).ignore)
    yield toDelete.size

object InMemoryBlockStore:
  def make(
      primary: BlobStore,
      resolver: BlockResolver,
      others: Seq[BlobStore] = Seq.empty
  ): UIO[InMemoryBlockStore] =
    Ref.make(Map.empty[BlockKey, BlockState]).map { ref =>
      val all = (primary +: others).map(bs => bs.id -> bs).toMap
      new InMemoryBlockStore(ref, all, primary, resolver)
    }
