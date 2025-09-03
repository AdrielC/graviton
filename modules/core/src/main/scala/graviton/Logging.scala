package graviton

import zio.{Cause, FiberRef, Random, Unsafe, ZIO, ZLayer, LogLevel, Runtime}
import zio.logging.{
  LogAnnotation as ZLogAnnotation,
  ConsoleLoggerConfig,
  LogFormat,
  LogFilter,
  consoleLogger
}

object Logging:
  private val correlationIdAnnotation =
    ZLogAnnotation[String]("correlation-id", (_, b) => b, identity)

  private val correlationIdRef: FiberRef[Option[String]] =
    Unsafe.unsafe(implicit u => FiberRef.unsafe.make[Option[String]](None))

  def layer(level: LogLevel = LogLevel.Info): ZLayer[Any, Nothing, Unit] =
    Runtime.removeDefaultLoggers >>> consoleLogger(
      ConsoleLoggerConfig(
        format = LogFormat.colored,
        filter = LogFilter.LogLevelByNameConfig(level)
      )
    )

  private def logAround[R, E <: Throwable, A](
      name: String
  )(zio: ZIO[R, E, A]): ZIO[R, E, A] =
    ZIO.logInfo(s"Starting $name") *>
      zio.tapBoth(
        e => ZIO.logErrorCause(s"$name failed", Cause.fail(e)),
        _ => ZIO.logInfo(s"Finished $name")
      )

  def withCorrelation[R, E <: Throwable, A](
      name: String
  )(zio: ZIO[R, E, A]): ZIO[R, E, A] =
    for
      current <- correlationIdRef.get
      cid <- current match
        case Some(id) => ZIO.succeed(id)
        case None     => Random.nextUUID.map(_.toString)
      res <- correlationIdRef.locally(Some(cid))(
        logAround(name)(zio) @@ correlationIdAnnotation(cid)
      )
    yield res
