package graviton

package impl

import graviton.*
import zio.*
import zio.stream.*
import java.time.OffsetDateTime
import java.net.URI
import java.nio.file.Path
import graviton.core.BinaryAttributes

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

  def fetch(
    hash: Hash,
    download: => Task[Bytes],
    binaryAttributes: BinaryAttributes,
    useCache: Boolean,
  ): ZIO[Scope, Throwable, Bytes] =


    def updateCache(hash: Hash, bytes: ZIO[Scope, Nothing, Bytes], expiresAt: OffsetDateTime, lastAccessedAt: Option[OffsetDateTime]): UIO[Unit] =

        def newBytes: ZIO[Scope, Throwable, ZIO[Scope, Nothing, Bytes]] =
            ZIO.suspendSucceed:

                val sink = 
                    ZSink.count.mapZIO(s => cacheSize.get.filterOrFail(size => s + size > maxCacheSize)(
                                        GravitonError.PolicyViolation("cache size exceeds limit: " + maxCacheSize
                                    )))
                                    .zipParRight(ZSink.fromFileURI(cachePath(hash)))
                   tmpSink <- ZIO.succeed(sink)
                bytes.flatMap(_.run(
                    )
                
            

        clock.currentDateTime.zipPar(cache.get.map(_.get(hash))).flatMap: 
            case (now, maybeBytes) =>
                maybeBytes match
                    case Some((existingBytes, expiresAt, lastAccessedAt)) =>
                        if (now.isAfter(expiresAt)) || (now.isEqual(expiresAt)) then
                            cache.update(_.removed(hash))
                        else
                            cache.update(_.updated(hash, (newBytes, expiresAt, Some(now))))
                    case None =>
                        cache.update(_.updated(hash, (newBytes, expiresAt, Some(now))))
            

    clock.currentDateTime.zipPar(cache.get).flatMap { (now, map) =>
      map.get(hash) match
        case Some((bytes, expiresAt, lastAccessedAt)) => 
            if 
            ZStream.unwrapScoped(bytes)
        case None =>
          for
            data <- download
            bytes = Bytes(ZStream.fromChunk(data))
            _ <- cache.update(_.updated(hash, ZIO.succeed(data)))
          yield data
    }

  def invalidate(hash: Hash): UIO[Unit] =
    ???

