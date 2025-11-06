package graviton.fs

import graviton.*
import zio.*
import zio.ZLayer.Derive.Default
import zio.cache.*
import zio.stream.*
import zio.Duration
import java.nio.file.{Files, Path}
import io.github.iltotore.iron.constraint.string.ValidURL
import java.net.URI

/**
 * Local-disk [[CacheStore]] backed by [[zio.cache.Cache]]. Cached entries are
 * stored under `root/<hash-hex>` and validated against the expected hash
 * before being served.
 */
final class DiskCacheStore private (
  root: URI,
  cache: Cache[Hash, Throwable, Chunk[Byte]],
  loaderRef: FiberRef[Hash => Task[Chunk[Byte]]],
) extends CacheStore:

  private def pathFor(hash: Hash): Path =
    Path.of(root.toASCIIString()).resolve(hash.hex)

  private def fromChunk(ch: Chunk[Byte]): Bytes = Bytes(ZStream.fromChunk(ch))

  def fetch(
    hash: Hash,
    download: => Task[Bytes],
    useCache: Boolean,
  ): Task[Bytes] =
    if !useCache then
      for
        data  <- download
        chunk <- data.runCollect
        _     <- DiskCacheStore.verify(hash, chunk)
      yield fromChunk(chunk)
    else
      loaderRef.locally((_: Hash) => download.flatMap(_.runCollect)) {
        cache.get(hash).map(fromChunk)
      }

  def invalidate(hash: Hash): UIO[Unit] =
    cache.invalidate(hash) *>
      ZIO
        .attempt(Files.deleteIfExists(pathFor(hash)))
        .mapError(e => GravitonError.NotFound(s"failed to invalidate cache for hash ${hash.hex}", e))
        .ignoreLogged

object DiskCacheStore:

  case class Conf(
    root: URI,
    capacity: Int = 1024,
    ttl: Duration = Duration.Infinity,
  )

  object Conf:

    given config: Config[Conf] =

      (Config.uri("root").withDefault(URI.create("file:///tmp/graviton/cache")) ++
        Config.int("capacity").withDefault(1024) ++
        Config.duration("ttl").withDefault(Duration.Infinity))
        .map(Conf(_, _, _))

  private def verify(hash: Hash, data: Chunk[Byte]): Task[Unit] =
    for
      dig <- Hashing.compute(Bytes(ZStream.fromChunk(data)), hash.algo)
      _   <- ZIO
               .fail(RuntimeException("hash mismatch"))
               .unless(dig.toList == hash.bytes.toList)
    yield ()

  private def loader(
    root: Path,
    ref: FiberRef[Hash => Task[Chunk[Byte]]],
  )(hash: Hash): Task[Chunk[Byte]] =
    val path                                = root.resolve(hash.hex)
    def downloadAndWrite: Task[Chunk[Byte]] =
      for
        remote <- ref.get
        chunk  <- remote(hash)
        _      <- verify(hash, chunk)
        _      <- ZIO.attempt(Files.createDirectories(path.getParent))
        _      <- ZIO.attempt(Files.write(path, chunk.toArray))
      yield chunk
    for
      exists <- ZIO.attempt(Files.exists(path))
      chunk  <-
        if exists then
          for
            arr <- ZIO.attempt(Files.readAllBytes(path)).map(Chunk.fromArray)
            ok  <- Hashing
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
    ttl: Duration = Duration.Infinity,
  ): ZIO[Scope, Nothing, DiskCacheStore] =
    for
      ref   <- FiberRef.make[Hash => Task[Chunk[Byte]]]((_: Hash) => ZIO.fail(RuntimeException("no loader")))
      cache <- Cache.make[Hash, Any, Throwable, Chunk[Byte]](
                 capacity,
                 ttl,
                 Lookup(loader(root, ref)),
               )
    yield DiskCacheStore(root, cache, ref)

  def layer(
    root: URI,
    capacity: Int = 1024,
    ttl: Duration = Duration.Infinity,
  ): ZLayer[Any, Nothing, DiskCacheStore] =
    ZLayer.scoped(make(Path.of(root.toASCIIString()), capacity, ttl))

  def default: ULayer[DiskCacheStore] =
    ZLayer.fromZIO:
      for
        root  <- ZIO.config[Conf]
        layer <- make(Path.of(root.root.toASCIIString()), root.capacity, root.ttl)
      yield layer

    layer(
      Path.of(System.getProperty("user.home"), ".graviton", "cache"),
      capacity = 1024,
      ttl = Duration.Infinity,
    )
