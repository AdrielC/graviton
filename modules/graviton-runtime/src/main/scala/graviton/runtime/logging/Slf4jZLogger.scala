package graviton.runtime.logging

import org.slf4j.LoggerFactory
import zio.*

/**
 * A small ZIO logger that emits to SLF4J (and therefore Log4j2 in this repo).
 *
 * This avoids pulling in an additional zio-logging module while still routing ZIO logs
 * through the app's configured SLF4J backend.
 */
object Slf4jZLogger:

  val live: ZLogger[String, Unit] =
    new ZLogger[String, Unit] {
      override def apply(
        trace: Trace,
        fiberId: FiberId,
        logLevel: LogLevel,
        message: () => String,
        cause: Cause[Any],
        context: FiberRefs,
        spans: List[LogSpan],
        annotations: Map[String, String],
      ): Unit = {
        val logger = LoggerFactory.getLogger("graviton")

        val rendered =
          if annotations.isEmpty then message()
          else {
            val ann = annotations.toList.sortBy(_._1).map { case (k, v) => s"$k=$v" }.mkString(" ")
            s"${message()} ($ann)"
          }

        val throwable: Throwable | Null =
          cause.failureOption match
            case Some(t: Throwable) => t
            case _                  => null

        val renderedWithCause =
          if cause.isEmpty then rendered
          else s"$rendered\n${cause.prettyPrint}"

        logLevel match
          case LogLevel.Trace   =>
            if throwable == null then logger.trace(renderedWithCause) else logger.trace(rendered, throwable)
          case LogLevel.Debug   =>
            if throwable == null then logger.debug(renderedWithCause) else logger.debug(rendered, throwable)
          case LogLevel.Info    =>
            if throwable == null then logger.info(renderedWithCause) else logger.info(rendered, throwable)
          case LogLevel.Warning =>
            if throwable == null then logger.warn(renderedWithCause) else logger.warn(rendered, throwable)
          case LogLevel.Error   =>
            if throwable == null then logger.error(renderedWithCause) else logger.error(rendered, throwable)
          case LogLevel.Fatal   =>
            if throwable == null then logger.error(renderedWithCause) else logger.error(rendered, throwable)
      }
    }
