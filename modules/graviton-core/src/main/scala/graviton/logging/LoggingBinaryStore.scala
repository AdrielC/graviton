package graviton.logging

import graviton.{BinaryId, BinaryStore}
import graviton.ranges.ByteRange
import zio.*
import zio.logging.logContext
import zio.stream.*

final case class LoggingBinaryStore(underlying: BinaryStore) extends BinaryStore:
  import LoggingBinaryStore.*

  private def around[A](name: String)(fa: IO[Throwable, A]): IO[Throwable, A] =
    loggingActive.get.flatMap {
      case true  => fa
      case false =>
        logContext.get.flatMap { ctx =>
          ctx.get(cidKey) match
            case Some(_) =>
              loggingActive.locally(true) {
                ZIO.logInfo(s"$name start") *> fa.tapBoth(
                  err => ZIO.logErrorCause(s"$name error", Cause.fail(err)),
                  _ => ZIO.logInfo(s"$name finish"),
                )
              }
            case None    =>
              Random.nextUUID.flatMap { cid =>
                ZIO.logAnnotate(cidKey, cid.toString) {
                  loggingActive.locally(true) {
                    ZIO.logInfo(s"$name start") *> fa.tapBoth(
                      err => ZIO.logErrorCause(s"$name error", Cause.fail(err)),
                      _ => ZIO.logInfo(s"$name finish"),
                    )
                  }
                }
              }
        }
    }

  override def put: ZSink[Any, Throwable, Byte, Nothing, BinaryId] =
    ZSink.unwrapScoped {
      loggingActive.get.flatMap {
        case true  => ZIO.succeed(underlying.put)
        case false =>
          logContext.get.flatMap { ctx =>
            ctx.get(cidKey) match
              case Some(_) =>
                loggingActive.locally(true) {
                  for
                    _   <- ZIO.logInfo("put start")
                    sink = underlying.put
                  yield sink
                    .mapZIO(id => ZIO.logInfo("put finish").as(id))
                    .mapErrorZIO(err => ZIO.logErrorCause("put error", Cause.fail(err)).as(err))
                }
              case None    =>
                Random.nextUUID.flatMap { cid =>
                  ZIO.logAnnotate(cidKey, cid.toString) {
                    loggingActive.locally(true) {
                      for
                        _   <- ZIO.logInfo("put start")
                        sink = underlying.put
                      yield sink
                        .mapZIO(id => ZIO.logInfo("put finish").as(id))
                        .mapErrorZIO(err =>
                          ZIO
                            .logErrorCause("put error", Cause.fail(err))
                            .as(err)
                        )
                    }
                  }
                }
          }
      }
    }

  override def get(
    id: BinaryId,
    range: Option[ByteRange],
  ): IO[Throwable, Option[graviton.Bytes]] =
    around("get")(underlying.get(id, range))

  override def delete(id: BinaryId): IO[Throwable, Boolean] =
    around("delete")(underlying.delete(id))

  override def exists(id: BinaryId): IO[Throwable, Boolean] =
    around("exists")(underlying.exists(id))

object LoggingBinaryStore:
  private val cidKey                   = "correlation-id"
  val loggingActive: FiberRef[Boolean] =
    Unsafe.unsafe(implicit u => FiberRef.unsafe.make(false))

  val layer: ZLayer[BinaryStore, Nothing, BinaryStore] =
    ZLayer.fromFunction(LoggingBinaryStore(_))
