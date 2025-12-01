package graviton

package impl

import graviton.*
import zio.*
import zio.stream.*
import java.time.OffsetDateTime
import java.net.URI
import java.nio.file.{Files, Path, Paths}
import graviton.core.model.FileSize

final case class InMemoryCacheStore(
  cache: Ref.Synchronized[Map[
    Hash.SingleHash,
    (
      bytes: ZIO[Scope, Nothing, Bytes],
      expiresAt: OffsetDateTime,
      lastAccessedAt: Option[OffsetDateTime],
    ),
  ]],
  ttl: Duration,
  clock: Clock,
  cacheRoot: URI,
  maxCacheSize: FileSize,
  cacheSize: Ref[FileSize],
) extends CacheStore:

  private[graviton] def cachePath(hash: Hash.SingleHash): URI =
    cacheRoot.resolve(Path.of(s"cache/${hash.algo.canonicalName}/${hash.hex}").toUri())

  protected def updateCache(
    hash: Hash.SingleHash,
    bytes: ZIO[Scope, Throwable, Bytes],
    expiresAt: OffsetDateTime,
    lastAccessedAt: Option[OffsetDateTime],
  )(using Trace): UIO[Unit] =

    def newBytes: ZIO[Scope, Nothing, Bytes] =
      ZIO.suspendSucceed:

        val cacheFile = cachePath(hash)

        val sink =
          ZSink.count
            .mapZIO(s =>
              cacheSize.get
                .filterOrFail(size => s + size > maxCacheSize)(GravitonError.PolicyViolation("cache size exceeds limit: " + maxCacheSize))
            )
            .zipParRight(ZSink.fromFileURI(cacheFile))
        for
          tmpSink <- ZIO.succeed(sink)
          _       <- bytes.flatMap(_.run(tmpSink)).ignoreLogged
          onErr    =
            ZIO.attemptBlocking(Paths.get(cacheFile).toFile.delete()).ignoreLogged *>
              cache.update(_.removed(hash))

          bts <- ZIO
                   .succeed(Bytes(ZStream.fromFileURI(cacheFile)))
                   .cached(ttl)
                   .withClock(clock)
                   .onError(cause => ZIO.logErrorCause(cause) *> onErr)
                   .ensuring(
                     ZIO.succeed(Paths.get(cacheFile).toFile.deleteOnExit()) *>
                       updateCache(hash, bytes, expiresAt, lastAccessedAt)
                   )
          _   <- cache.update(
                   _.updated(
                     hash,
                     (
                       bytes = ZIO.succeed(Bytes(ZStream.unwrapScoped(bts))),
                       expiresAt = expiresAt,
                       lastAccessedAt = None,
                     ),
                   )
                 )
        yield Bytes(ZStream.unwrapScoped(bts))

    clock.currentDateTime
      .zipPar(cache.get.map(_.get(hash)))
      .flatMap:
        case (now, maybeBytes) =>
          maybeBytes match
            case Some((existingBytes, expiresAt, lastAccessedAt)) =>
              updateCache(hash, existingBytes, expiresAt, lastAccessedAt)
            case None                                             =>
              updateCache(hash, newBytes, expiresAt, lastAccessedAt)

  def fetch(
    hash: Hash.SingleHash,
    download: => Task[Bytes],
    useCache: Boolean = true,
  ): ZIO[Scope, Throwable, Bytes] =

    if (useCache) then
      for
        now   <- clock.currentDateTime
        map   <- cache.get
        bytes <-
          map.get(hash) match
            case Some((bytes, expiresAt, lastAccessedAt)) =>
              if (now.isAfter(expiresAt)) || (now.isEqual(expiresAt)) then
                cache.update(_.removed(hash)) *>
                  bytes
              else bytes
            case None                                     =>
              for
                bytes <- download.cached(ttl).withClock(clock)
                _     <- updateCache(hash, bytes, now.plus(ttl), None)
              yield Bytes(ZStream.unwrapScoped(bytes))
          end match
      yield bytes
      end for
    else
      for bytes <- download
      yield bytes
      end for

  def invalidate(hash: Hash.SingleHash): UIO[Unit] =
    cache.update(_.removed(hash)) *>
      ZIO.attemptBlocking(Paths.get(cachePath(hash).toASCIIString()).toFile.delete()).ignoreLogged

object InMemoryCacheStore:
  object Defaults:
    inline def oneGB: FileSize = FileSize.GB[1L]
    inline def defaultClock    = Clock.ClockLive

  case class Conf(
    ttl: Option[Duration],
    clock: Option[Clock],
    cacheRoot: Option[URI],
    maxCacheSize: Option[FileSize],
  )

  object Conf:

    inline given config: Config[Conf] =
      (Config
        .duration("ttl")
        .withDefault(10.minutes)
        .optional
        .??("Time to live for cached items") ++
        Config
          .uri("cacheRoot")
          .withDefault(
            URI
              .create(java.lang.System.getProperty("user.home"))
              .resolve(URI.create("/.graviton/cache"))
          )
          .optional
          .??("Root directory for cache") ++
        Config
          .long("maxCacheSize")
          .mapOrFail(FileSize.either(_).left.map(e => Config.Error.InvalidData(Chunk("maxCacheSize"), e)))
          .withDefault(Defaults.oneGB)
          .optional
          .??("Maximum size of cache"))
        .map { case (ttl, cacheRoot, maxCacheSize) =>
          Conf(
            ttl = ttl,
            clock = Some(Defaults.defaultClock),
            cacheRoot = cacheRoot,
            maxCacheSize = maxCacheSize,
          )
        }

  def make(
    ttl: Duration,
    clock: Clock,
    cacheRoot: URI,
    maxCacheSize: FileSize,
  ): UIO[InMemoryCacheStore] =
    for
      cache     <- Ref.Synchronized.make(
                     Map.empty[
                       Hash.SingleHash,
                       (
                         bytes: ZIO[Scope, Nothing, Bytes],
                         expiresAt: OffsetDateTime,
                         lastAccessedAt: Option[OffsetDateTime],
                       ),
                     ]
                   )
      cacheSize <- Ref.make(FileSize.zero)
    yield InMemoryCacheStore(
      cache,
      ttl,
      clock,
      cacheRoot,
      maxCacheSize,
      cacheSize,
    )
  end make

  def layer(
    ttl: Duration,
    clock: Clock,
    cacheRoot: URI,
    maxCacheSize: FileSize,
  ): ULayer[InMemoryCacheStore] =
    ZLayer.scoped(make(ttl, clock, cacheRoot, maxCacheSize))

  inline def default: ZLayer[Any, Throwable, InMemoryCacheStore] =
    ZLayer.fromZIO:
      for
        conf      <- ZIO.config[Conf]
        cacheHome <- System.property("user.home")
        c          = cacheHome.flatMap(s => scala.util.Try(URI.create(s)).toOption).orElse(conf.cacheRoot)
        cacheRoot <- ZIO
                       .attempt(c.map(_.resolve(URI.create("/.graviton/cache"))))
                       .flatMap(f => ZIO.attempt(f.getOrElse(Files.createTempDirectory("file:///tmp/graviton-cache").toUri())))
        store     <- make(
                       conf.ttl.getOrElse(24.hours),
                       conf.clock.getOrElse(Defaults.defaultClock),
                       cacheRoot,
                       conf.maxCacheSize.getOrElse(Defaults.oneGB),
                     )
      yield store
