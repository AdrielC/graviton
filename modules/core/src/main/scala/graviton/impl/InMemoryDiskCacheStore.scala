package graviton

package impl

import graviton.*
import zio.*
import zio.stream.*
import java.time.OffsetDateTime
import java.net.URI
import java.nio.file.{Path, Paths}

final case class InMemoryCacheStore(
    cache: Ref.Synchronized[Map[Hash, (
        bytes: ZIO[Scope, Nothing, Bytes], 
        expiresAt: OffsetDateTime,
        lastAccessedAt: Option[OffsetDateTime]
    )]],
    ttl: Duration,
    clock: Clock,
    cacheRoot: URI,
    maxCacheSize: Long,
    cacheSize: Ref[Long],
) extends CacheStore:


    private[graviton] def cachePath(hash: Hash): URI =
        cacheRoot.resolve(Path.of(s"cache/${hash.algo.canonicalName}/${hash.hex}").toUri())


    private[graviton] def updateCache(
        hash: Hash, 
        bytes: ZIO[Scope, Throwable, Bytes], 
        expiresAt: OffsetDateTime, 
        lastAccessedAt: Option[OffsetDateTime],
    )(using Trace): UIO[Unit] =

        def newBytes: ZIO[Scope, Nothing, Bytes] =
            ZIO.suspendSucceed:

                val cacheFile = cachePath(hash)

                val sink = 
                    ZSink.count.mapZIO(s => cacheSize.get.filterOrFail(size => s + size > maxCacheSize)(
                                        GravitonError.PolicyViolation("cache size exceeds limit: " + maxCacheSize
                                    )))
                                    .zipParRight(ZSink.fromFileURI(cacheFile))
                for 
                  tmpSink <- ZIO.succeed(sink)
                  _ <- bytes.flatMap(_.run(tmpSink)).ignoreLogged
                  onErr = 
                    ZIO.attemptBlocking(Paths.get(cacheFile).toFile.delete()).ignoreLogged *> 
                    cache.update(_.removed(hash))

                  bts <- ZIO.succeed(Bytes(ZStream.fromFileURI(cacheFile)))
                    .cached(ttl).withClock(clock)
                    .onError(cause => ZIO.logErrorCause(cause) *> onErr)
                    .ensuring(
                        ZIO.succeed(Paths.get(cacheFile).toFile.deleteOnExit()) *>
                        updateCache(hash, bytes, expiresAt, lastAccessedAt)
                    )
                  _ <- cache.update(_.updated(hash, (
                    bytes = ZIO.succeed(Bytes(ZStream.unwrapScoped(bts))), 
                    expiresAt = expiresAt,
                    lastAccessedAt = None
                )))
                yield Bytes(ZStream.unwrapScoped(bts))


        clock.currentDateTime.zipPar(cache.get.map(_.get(hash))).flatMap: 
            case (now, maybeBytes) =>
                maybeBytes match
                    case Some((existingBytes, expiresAt, lastAccessedAt)) =>
                        if (now.isAfter(expiresAt)) || (now.isEqual(expiresAt)) then
                            cache.update(_.removed(hash))
                        else
                            ZIO.unit
                    case None =>
                        updateCache(hash, newBytes, expiresAt, lastAccessedAt)

    def fetch(
        hash: Hash,
        download: => Task[Bytes],
        useCache: Boolean = true,
    ): ZIO[Scope, Throwable, Bytes] =

        if (useCache) then
            for
                now <- clock.currentDateTime
                map <- cache.get
                bytes <-
                    map.get(hash) match
                        case Some((bytes, expiresAt, lastAccessedAt)) => 
                            if (now.isAfter(expiresAt)) || (now.isEqual(expiresAt)) then
                                cache.update(_.removed(hash)) *>
                                bytes
                            else
                                bytes
                        case None =>
                            for
                                bytes <- download.cached(ttl).withClock(clock)
                                _ <- updateCache(hash, bytes, now.plus(ttl), None)
                            yield Bytes(ZStream.unwrapScoped(bytes))
                    end match
            yield bytes
            end for
        else 
            for
                bytes <- download
            yield bytes
            end for


    def invalidate(hash: Hash): UIO[Unit] =
        cache.update(_.removed(hash)) *>
        ZIO.attemptBlocking(Paths.get(cachePath(hash).toASCIIString()).toFile.delete()).ignoreLogged