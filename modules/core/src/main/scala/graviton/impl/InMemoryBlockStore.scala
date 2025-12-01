package graviton.impl

import graviton.*
import graviton.core.model.*
import zio.*
import zio.stream.*
import java.time.Instant
import graviton.domain.NonNegInt

import graviton.core.BlockManifestEntry
import graviton.core.{mapZIO, toNonEmptyChunk}

final case class BlockState(refs: NonNegInt, unreferencedAt: Option[Instant])

final class InMemoryBlockStore private (
  index: Ref.Synchronized[Map[BlockKey, BlockState]],
  stores: Map[BlobStoreId, BlobStore],
  primary: BlobStore,
  resolver: BlockResolver,
) extends BlockStore:

  private def backendFailure(err: Throwable): GravitonError =
    GravitonError.BackendUnavailable(Option(err.getMessage).getOrElse(err.toString), Some(err))

  // private val MaxBlockSize: Int = Limits.MAX_BLOCK_SIZE_IN_BYTES

  def putBlock(block: Block): ZIO[Any, Throwable, NonEmptyChunk[BlockKey]] =
    for
      dig   <- Hashing.compute(block.toBytes)
      keys  <- dig.bytes.mapZIO: (algo, hash) => 
        val key = BlockKey(Hash.SingleHash(algo, hash), block.blockSize)
        index.modifyZIO(m => m.get(key).fold(ZIO.succeed(key -> m.updated(key, BlockState(NonNegInt(1), None)))) { state => 
          val data = Bytes(ZStream.fromChunk(block.bytes))
          for
            _      <- primary.write(key, data)
            status <- primary.status
            sector = BlockSector(primary.id, status)
            _      <- resolver.record(key, sector)
            _      <- index.update(_ + (key -> BlockState(NonNegInt(1), None)))
          yield key -> m.updated(key, state.copy(refs = NonNegInt.applyUnsafe(state.refs.value + 1), unreferencedAt = None))
        })

    yield keys.toNonEmptyChunk.map(_._2)

  def storeBlocks: ZPipeline[Any, GravitonError, Block, BlockManifestEntry] =
    ZPipeline.unwrapScoped:
      for
        algos <- Hashing.ref.getHashAlgos
        hasher <- Hashing
                    .hasher(algos)
                    .mapError(backendFailure)

        fileHasher <- Hashing
                        .hasher(algos)
                        .mapError(backendFailure)

        pipeline = ZPipeline.identity[Block].mapZIO { block =>
                     for
                       _     <- hasher.update(block).mapError(backendFailure)
                       dig   <- hasher.digest.mapError(backendFailure)
                       _     <- fileHasher.update(block).mapError(backendFailure)
                       keys  = dig.map((algo, hash) => BlockKey(Hash.SingleHash(algo, hash), block.blockSize))
                       states <- ZIO.foreach(keys.toNonEmptyChunk) { case (algo, key) => index.modifyZIO { m => m.get(key) match
                                  case Some(st) =>
                                    val update = m.updated(key, st.copy(refs = NonNegInt.applyUnsafe(st.refs.value + 1), unreferencedAt = None))
                                    ZIO.succeed((update, update))
                                  case None     =>
                                    val data = Bytes(ZStream.fromChunk(block.bytes))
                                    for
                                      _      <- primary.write(key, data).mapError(backendFailure)
                                      status <- primary.status
                                      _      <- resolver.record(key, BlockSector(primary.id, status))
                                      state  = m.updated(key, BlockState(NonNegInt(1), None))
                                    yield state -> state
                                }}
                       entries <- ZIO.foreach(states)(n => ZIO.fromOption(NonEmptyChunk.fromIterableOption(n.keys.map(k => k.toBinaryKey)))
                        .mapError(_ => backendFailure(
                          Throwable("Failed to convert block keys to NonEmptyChunk")
                        )))
                        .map(_.flatten)

                     yield BlockManifestEntry(Index.zero, block.blockSize, entries)
                   }
      yield pipeline

  def put: ZSink[Any, GravitonError, Byte, Nothing, NonEmptyChunk[BlockKey]] =
    ZSink
      .collectAll[Byte]
      .mapZIO: data =>
        val bytesStream = Bytes(data)
        for
          dig   <- Hashing.compute(bytesStream).mapError(backendFailure)
          keys  = dig.map((algo, hash) => BlockKey(Hash.SingleHash(algo, hash), BlockSize.applyUnsafe(data.length)))
          stOpt <- keys.toNonEmptyChunk.mapZIO(key => index.get.flatMap(_.get(key._2) match
                     case Some(st) =>
                       index.update(_.updated(key._2, st.copy(refs = NonNegInt.applyUnsafe(st.refs.value + 1), unreferencedAt = None)))
                     case None     =>
                       for
                         _  <- primary.write(key._2, Bytes(data)).mapError(backendFailure)
                         st <- primary.status
                         _  <- resolver.record(key._2, BlockSector(primary.id, st))
                         _  <- index.update(_ + (key._2 -> BlockState(NonNegInt(1), None)))
                       yield ()))
        yield keys.toNonEmptyChunk.map(_._2)

  def get(
    key: BlockKey,
    range: Option[ByteRange] = None,
  ): IO[Throwable, Option[Bytes]] =
    resolver.resolve(key).flatMap { sectors =>
      def loop(rem: Chunk[BlockSector]): IO[Throwable, Option[Bytes]] =
        rem.headOption match
          case None      => ZIO.succeed(None)
          case Some(sec) =>
            stores.get(sec.blobStoreId) match
              case None        => loop(rem.drop(1))
              case Some(store) =>
                store.read(key, range).flatMap {
                  case None => loop(rem.drop(1))
                  case s    => ZIO.succeed(s)
                }
      loop(sectors)
    }

  def has(key: BlockKey): IO[Throwable, Boolean] =
    resolver.resolve(key).flatMap { sectors =>
      def check(rem: Chunk[BlockSector]): IO[Throwable, Boolean] =
        rem.headOption match
          case None      => ZIO.succeed(false)
          case Some(sec) =>
            stores.get(sec.blobStoreId) match
              case None        => check(rem.drop(1))
              case Some(store) =>
                store.read(key).map(_.isDefined).flatMap {
                  case true  => ZIO.succeed(true)
                  case false => check(rem.drop(1))
                }
      check(sectors)
    }

  def delete(key: BlockKey): IO[Throwable, Boolean] =
    Clock.instant.flatMap { now =>
      index.modify { m =>
        m.get(key) match
          case Some(state) if state.refs > 0 =>
            val updated =
              if state.refs == NonNegInt(1) then state.copy(refs = NonNegInt(0), unreferencedAt = Some(now))
              else state.copy(refs = NonNegInt.option(state.refs - 1).getOrElse(NonNegInt(0)), unreferencedAt = None)
            (true, m.updated(key, updated))
          case _                             => (false, m)
      }
    }

  def list(selector: BlockKeySelector): ZStream[Any, Throwable, BlockKey] =
    ZStream.fromZIO(index.get).flatMap { set =>
      val all = set.collect { case (k, st) if st.refs > 0 => k }.toVector
      selector.prefix match
        case None    => ZStream.fromIterable(all)
        case Some(p) =>
          ZStream.fromIterable(
            all.filter(_.hash.bytes.bytes.take(p.length) == p.toChunk)
          )
    }

  def gc(config: GcConfig): UIO[Int] =
    for
      now      <- Clock.instant
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
      _        <- ZIO.foreachDiscard(toDelete)(k => primary.delete(k).ignore)
    yield toDelete.size

object InMemoryBlockStore:

  def live(backups: Seq[BlobStore] = Nil): URLayer[BlobStore & BlockResolver, InMemoryBlockStore] =
    ZLayer.fromFunction((primary: BlobStore, resolver: BlockResolver) => ZLayer.fromZIO(make(primary, resolver, backups))).flatten

  val layer: URLayer[Ref.Synchronized[Map[BlockKey, BlockState]] & Map[BlobStoreId, BlobStore] & (BlobStore & BlockResolver), InMemoryBlockStore] =
    ZLayer.derive[InMemoryBlockStore]

  def make(
    primary: BlobStore,
    resolver: BlockResolver,
    others: Seq[BlobStore] = Seq.empty,
  ): UIO[InMemoryBlockStore] =
    Ref.Synchronized.make(Map.empty[BlockKey, BlockState]).map { ref =>
      val all = (primary +: others).map(bs => bs.id -> bs).toMap
      new InMemoryBlockStore(ref, all, primary, resolver)
    }
