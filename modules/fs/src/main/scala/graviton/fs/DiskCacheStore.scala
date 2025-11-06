package graviton.fs

import graviton.*
import zio.*
import zio.ZLayer.Derive.Default
import zio.cache.*
import zio.stream.*
import zio.Duration
import java.nio.file.{Files, Path}

import java.net.URI

/**
 * Local-disk [[CacheStore]] backed by [[zio.cache.Cache]]. Cached entries are
 * stored under `root/<hash-hex>` and validated against the expected hash
 * before being served.
 */
final class DiskCacheStore private (
  root: DiskCacheStore.Root,
  cache: Cache[Hash, Throwable, Bytes],
  loaderRef: FiberRef[Hash => Task[Chunk[Byte]]],
) extends CacheStore:

  export DiskCacheStore.{toURI, toPath, given}

  private def pathFor(hash: Hash): Path =
    Path.of(root.toURI.toASCIIString()).resolve(hash.hex)

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
        cache.get(hash)
      }

  def invalidate(hash: Hash): UIO[Unit] =
    cache.invalidate(hash) *>
      ZIO
        .attempt(Files.deleteIfExists(pathFor(hash)))
        .mapError(e => GravitonError.NotFound(s"failed to invalidate cache for hash ${hash.hex}", Some(e)))
        .ignoreLogged

object DiskCacheStore:

  case class Conf(
    root: URI,
    capacity: Int = 1024,
    ttl: Duration = Duration.Infinity,
  )

  object Conf:

    object Defaults:
      val defaultCapacity       = 1024 * 1024 * 1024 * 10 // 10GB
      val defaultTtl            = 24.hours
      val defaultRoot: URI      = URI.create("file:///tmp/")
      val defaultCacheDir: Path = Path.of("/.graviton/cache")

      val staticConf: Conf = Conf(
        URI.create("file:///tmp/.graviton/cache"),
        defaultCapacity,
        defaultTtl,
      )
    end Defaults

    def defaultFromSystem: Task[Conf] =
      for
        home <- System
                  .property("user.home")
                  .flatMap(ZIO.fromOption(_).map(URI.create))
                  .orElse(ZIO.succeed(Defaults.defaultRoot))
        root <- ZIO
                  .attempt(home.resolve(Defaults.defaultCacheDir.toUri()))
                  .orElse(ZIO.attempt(Defaults.defaultCacheDir.toUri()))
                  .mapError(e => GravitonError.BackendUnavailable(s"failed to resolve default cache directory", Some(e)))
      yield Conf(root, Defaults.defaultCapacity, Defaults.defaultTtl)

    // Create a custom compile time macro with MirrorOrProduct
    import scala.deriving.*

    // case class to NamedTuple

    trait ToMap[-A]:
      extension (a: A) inline def toMap(nested: String*): Map[String, String]
    end ToMap

    object ToMap:

      given [A <: Product] => ToMap[A]:
        extension (a: A)
          inline def toMap(nested: String*): Map[String, String] =
            val mirror = compiletime.summonInline[Mirror.ProductOf[A]]
            val m      = mirror.fromProduct(a)

            m.productElementNames
              .zip(m.productIterator)
              .map { case (name, value) =>
                s"$name" -> s"$value"
              }
              .toMap
        end extension
      end given

    given config: Config[Conf] =

      (Config.uri("root").withDefault(Defaults.staticConf.root) ++
        Config.int("capacity").withDefault(Defaults.staticConf.capacity) ++
        Config.duration("ttl").withDefault(Defaults.staticConf.ttl))
        .map(Conf(_, _, _))

    export ToMap.given

    val defaultProvider =
      val packages = List("DiskCacheStore", "fs", "graviton")
      ConfigProvider
        .fromMap(
          Defaults.staticConf.toMap(packages*)
        )
        .orElse(ConfigProvider.propsProvider)
        .orElse(ConfigProvider.envProvider)
        .nested(packages.reverse.mkString("."))

    given Default.WithContext[Any, Throwable, Conf] =
      Default.fromZIO(
        ZIO
          .config[Conf]
          .provideEnvironment(
            ZEnvironment(defaultProvider)
          )
          .orElse(defaultFromSystem)
      )

  private def verify(hash: Hash, data: Chunk[Byte]): Task[Unit] =
    for
      dig <- Hashing.compute(Bytes(ZStream.fromChunk(data)), hash.algo)
      _   <- ZIO
               .fail(RuntimeException("hash mismatch"))
               .unless(dig.toList == hash.bytes.toList)
    yield ()

  extension (uri: URI) def toPath: Path = Path.of(uri.toASCIIString())

  private def loader(
    root: URI,
    ref: FiberRef[Hash => Task[Chunk[Byte]]],
  )(hash: Hash): Task[Bytes] =
    val path = root.resolve(hash.hex)

    def downloadAndWrite: Task[Bytes] =
      for
        remote <- ref.get
        chunk  <- remote(hash)
        _      <- verify(hash, chunk)
        res    <- ZStream.fromChunk(chunk).run(ZSink.fromFileURI(path))
      yield Bytes(ZStream.fromChunk(chunk))
    for

      exists <- ZIO.attempt(Files.exists(path.toPath))
      chunk  <-
        if exists then
          for
            bytes <- ZIO.attempt(Bytes(ZStream.fromFileURI(path)))
            ok    <- Hashing
                       .compute(bytes, hash.algo)
                       .map(_ == hash.bytes)
            res   <- if ok then ZIO.succeed(bytes) else downloadAndWrite
          yield res
        else downloadAndWrite
    yield chunk

  /** Construct a [[DiskCacheStore]]. */
  def make(
    root: URI | Path,
    capacity: Int = 1024,
    ttl: Duration = Duration.Infinity,
  ): ZIO[Scope, Nothing, DiskCacheStore] =
    for
      ref   <- FiberRef.make[Hash => Task[Chunk[Byte]]]((_: Hash) => ZIO.fail(RuntimeException("no loader")))
      cache <- Cache.make[Hash, Any, Throwable, Bytes](
                 capacity,
                 ttl,
                 Lookup(loader(root.toURI, ref)),
               )
    yield DiskCacheStore(root, cache, ref)

  def layer(
    root: URI,
    capacity: Int = 1024,
    ttl: Duration = Duration.Infinity,
  ): ZLayer[Any, Nothing, DiskCacheStore] =
    ZLayer.scoped(make(root, capacity, ttl))

  def default: ZLayer[Any, Throwable, DiskCacheStore] =
    ZLayer.scoped:
      for
        conf  <- ZIO
                   .config[Conf]
                   .logError("failed to load cache config, using default")
                   .orElse(ZIO.service[Conf].provideLayer(Default[Conf].layer))
        layer <- make(conf.root, conf.capacity, conf.ttl)
      yield layer

  type Root = URI | Path
  extension (uri: Root)
    def toURI: URI = uri match
      case p: Path => p.toUri()
      case u: URI  => u
  end extension
