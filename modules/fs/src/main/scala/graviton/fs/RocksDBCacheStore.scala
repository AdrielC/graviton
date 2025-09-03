package graviton.fs

import graviton.*
import zio.*
import zio.cache.*
import zio.stream.*
import zio.Duration
import java.nio.file.Path
import zio.rocksdb.{RocksDB as ZRocksDB}

/** [[CacheStore]] backed by RocksDB for persistent storage. Cached entries are
  * keyed by the hex representation of the [[Hash]] and validated against the
  * expected digest before use.
  */
final class RocksDBCacheStore private (
    db: ZRocksDB,
    cache: Cache[Hash, Throwable, Chunk[Byte]],
    loaderRef: FiberRef[Hash => Task[Chunk[Byte]]]
) extends CacheStore:

  private def fromChunk(ch: Chunk[Byte]): Bytes = Bytes(ZStream.fromChunk(ch))

  def fetch(
      hash: Hash,
      download: => Task[Bytes],
      useCache: Boolean
  ): Task[Bytes] =
    if !useCache then
      for
        data <- download
        chunk <- data.runCollect
        _ <- RocksDBCacheStore.verify(hash, chunk)
      yield fromChunk(chunk)
    else
      loaderRef.locally((_: Hash) => download.flatMap(_.runCollect)) {
        cache.get(hash).map(fromChunk)
      }

  def invalidate(hash: Hash): UIO[Unit] =
    cache.invalidate(hash) *>
      db.delete(hash.bytes.toArray).ignore

object RocksDBCacheStore:
  private def verify(hash: Hash, data: Chunk[Byte]): Task[Unit] =
    for
      dig <- Hashing.compute(Bytes(ZStream.fromChunk(data)), hash.algo)
      _ <- ZIO
        .fail(RuntimeException("hash mismatch"))
        .unless(dig == hash.bytes)
    yield ()

  private def loader(
      db: ZRocksDB,
      ref: FiberRef[Hash => Task[Chunk[Byte]]]
  )(hash: Hash): Task[Chunk[Byte]] =
    val key = hash.bytes.toArray
    def downloadAndStore: Task[Chunk[Byte]] =
      for
        remote <- ref.get
        chunk <- remote(hash)
        _ <- verify(hash, chunk)
        _ <- db.put(key, chunk.toArray)
      yield chunk
    for
      existing <- db.get(key)
      chunk <- existing match
        case Some(arr) =>
          for
            ok <- Hashing
              .compute(
                Bytes(ZStream.fromChunk(Chunk.fromArray(arr))),
                hash.algo
              )
              .map(_ == hash.bytes)
            res <-
              if ok then ZIO.succeed(Chunk.fromArray(arr)) else downloadAndStore
          yield res
        case None => downloadAndStore
    yield chunk

  /** Construct a [[RocksDBCacheStore]]. */
  def make(
      path: Path,
      capacity: Int = 1024,
      ttl: Duration = Duration.Infinity
  ): ZIO[Scope, Throwable, RocksDBCacheStore] =
    for
      env <- ZRocksDB.live(path.toString).build
      db = env.get[ZRocksDB]
      ref <- FiberRef.make[Hash => Task[Chunk[Byte]]]((_: Hash) =>
        ZIO.fail(RuntimeException("no loader"))
      )
      cache <- Cache.make[Hash, Any, Throwable, Chunk[Byte]](
        capacity,
        ttl,
        Lookup(loader(db, ref))
      )
    yield RocksDBCacheStore(db, cache, ref)

  def layer(
      path: Path,
      capacity: Int = 1024,
      ttl: Duration = Duration.Infinity
  ): ZLayer[Any, Throwable, CacheStore] =
    ZLayer.scoped(make(path, capacity, ttl))
