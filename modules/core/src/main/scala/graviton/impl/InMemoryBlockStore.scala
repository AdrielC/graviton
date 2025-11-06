package graviton.impl

import graviton.*
import graviton.core.BinaryAttributes
import graviton.core.model.*
import zio.*
import zio.stream.*
import java.time.Instant
import graviton.domain.{HashBytes, NonNegInt}

import graviton.core.BlockManifestEntry
import graviton.core.FileKey
import graviton.core.BlockManifest
import graviton.core.BinaryKeyMatcher

final case class BlockState(refs: NonNegInt, unreferencedAt: Option[Instant])

final class InMemoryBlockStore private (
  index: Ref[Map[BlockKey, BlockState]],
  stores: Map[BlobStoreId, BlobStore],
  primary: BlobStore,
  resolver: BlockResolver,
) extends BlockStore
    with graviton.core.BlockStore:

  def putBlock(block: Block): ZIO[Any, Throwable, BlockKey] =
    ???

  def getBlock(key: BlockKey, range: Option[ByteRange] = None): IO[Throwable, Option[Block]] =
    ???

  def readBlocks(key: FileKey): IO[Throwable, Option[Blocks]] =
    ???

  def delete(key: FileKey): IO[Throwable, Boolean] =
    ???

  def exists(key: FileKey): IO[Throwable, Boolean] =
    ???

  def listKeys(matcher: BinaryKeyMatcher): ZStream[Any, Throwable, FileKey] =
    ???

  def findBinary(key: FileKey): IO[Throwable, Option[Bytes]] =
    ???

  def ingest: ZSink[Any, Throwable, Bytes, Nothing, (FileKey.CasKey.FileKey, BlockManifest)] =
    ???

  // private val MaxBlockSize: Int = Limits.MAX_BLOCK_SIZE_IN_BYTES

  def storeBlock(attrs: BinaryAttributes): ZPipeline[Any & Scope, GravitonError, Block, BlockManifestEntry] =
    val algo = HashAlgorithm.SHA256
    ZPipeline.unwrapScoped:
      for
        hasher  <- Hashing.hasher(algo).mapError(e => GravitonError.BackendUnavailable(Option(e.getMessage).getOrElse(e.toString)))
        pipeline = ZPipeline.identity[Block].mapZIO { block =>
                     for

                       u     <- hasher.update(block)
                       dig   <- hasher.digest
                       key    = BlockKey(Hash(dig, algo), block.blockSize)
                       state <- index.get.map(_.get(key))
                       _     <- state match
                                  case Some(st) =>
                                    index.update(
                                      _.updated(key, st.copy(refs = NonNegInt.applyUnsafe(st.refs.value + 1), unreferencedAt = None))
                                    )
                                  case None     =>
                                    val data = Bytes(ZStream.fromChunk(block.bytes))
                                    for
                                      _      <- primary.write(key, data).mapError(e => GravitonError.BackendUnavailable(e.getMessage))
                                      status <- primary.status
                                      _      <- resolver.record(key, BlockSector(primary.id, status))
                                      _      <- index.update(_ + (key -> BlockState(NonNegInt(1), None)))
                                    yield ()
                     yield BlockManifestEntry(Index.zero, block.blockSize, key.toBinaryKey)
                   }
      yield pipeline

  def put: ZSink[Any, GravitonError, Byte, Nothing, BlockKey] =
    ZSink.collectAll[Byte].mapZIO { data =>
      val key = BlockKey(Hash(HashBytes.applyUnsafe(data), HashAlgorithm.SHA256), BlockSize.applyUnsafe(data.length))
      index
        .update(_ + (key -> BlockState(NonNegInt(1), None)))
        .as(key)
    }

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
    index.get.map(_.get(key).exists(_.refs > 0))

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
            all.filter(_.hash.bytes.take(p.length) == p.toChunk)
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

  val layer: URLayer[Ref[Map[BlockKey, BlockState]] & Map[BlobStoreId, BlobStore] & (BlobStore & BlockResolver), InMemoryBlockStore] =
    ZLayer.derive[InMemoryBlockStore]

  def make(
    primary: BlobStore,
    resolver: BlockResolver,
    others: Seq[BlobStore] = Seq.empty,
  ): UIO[InMemoryBlockStore] =
    Ref.make(Map.empty[BlockKey, BlockState]).map { ref =>
      val all = (primary +: others).map(bs => bs.id -> bs).toMap
      new InMemoryBlockStore(ref, all, primary, resolver)
    }
