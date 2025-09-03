package graviton.fs

import graviton.*
import zio.*
import zio.cache.*
import zio.stream.*
import zio.Duration
import java.nio.file.{Files, Path}

/** Local-disk [[CacheStore]] backed by [[zio.cache.Cache]]. Cached entries are
  * stored under `root/<hash-hex>` and validated against the expected hash
  * before being served.
  */
final class DiskCacheStore private (
    root: Path,
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
        _ <- DiskCacheStore.verify(hash, chunk)
      yield fromChunk(chunk)
    else
      loaderRef.locally((_: Hash) => download.flatMap(_.runCollect)) {
        cache.get(hash).map(fromChunk)
      }

  def invalidate(hash: Hash): UIO[Unit] =
    cache.invalidate(hash) *>
      ZIO.attempt(Files.deleteIfExists(root.resolve(hash.hex))).ignore

object DiskCacheStore:
  private def verify(hash: Hash, data: Chunk[Byte]): Task[Unit] =
    for
      dig <- Hashing.compute(Bytes(ZStream.fromChunk(data)), hash.algo)
      _ <- ZIO
        .fail(RuntimeException("hash mismatch"))
        .unless(dig == hash.bytes)
    yield ()

  private def loader(
      root: Path,
      ref: FiberRef[Hash => Task[Chunk[Byte]]]
  )(hash: Hash): Task[Chunk[Byte]] =
    val path = root.resolve(hash.hex)
    def downloadAndWrite: Task[Chunk[Byte]] =
      for
        remote <- ref.get
        chunk <- remote(hash)
        _ <- verify(hash, chunk)
        _ <- ZIO.attempt(Files.createDirectories(path.getParent))
        _ <- ZIO.attempt(Files.write(path, chunk.toArray))
      yield chunk
    for
      exists <- ZIO.attempt(Files.exists(path))
      chunk <-
        if exists then
          for
            arr <- ZIO.attempt(Files.readAllBytes(path)).map(Chunk.fromArray)
            ok <- Hashing
              .compute(Bytes(ZStream.fromChunk(arr)), hash.algo)
              .map(_ == hash.bytes)
            res <- if ok then ZIO.succeed(arr) else downloadAndWrite
          yield res
        else downloadAndWrite
    yield chunk

  /** Construct a [[DiskCacheStore]]. */
  def make(
      root: Path,
      capacity: Int = 1024,
      ttl: Duration = Duration.Infinity
  ): ZIO[Scope, Nothing, DiskCacheStore] =
    for
      ref <- FiberRef.make[Hash => Task[Chunk[Byte]]]((_: Hash) =>
        ZIO.fail(RuntimeException("no loader"))
      )
      cache <- Cache.make[Hash, Any, Throwable, Chunk[Byte]](
        capacity,
        ttl,
        Lookup(loader(root, ref))
      )
    yield DiskCacheStore(root, cache, ref)

  def layer(
      root: Path,
      capacity: Int = 1024,
      ttl: Duration = Duration.Infinity
  ): ZLayer[Any, Nothing, CacheStore] =
    ZLayer.scoped(make(root, capacity, ttl))
