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

        val throwable = cause.squashTrace

        logLevel match
          case LogLevel.Trace   =>
            if throwable eq null then logger.trace(rendered) else logger.trace(rendered, throwable)
          case LogLevel.Debug   =>
            if throwable eq null then logger.debug(rendered) else logger.debug(rendered, throwable)
          case LogLevel.Info    =>
            if throwable eq null then logger.info(rendered) else logger.info(rendered, throwable)
          case LogLevel.Warning =>
            if throwable eq null then logger.warn(rendered) else logger.warn(rendered, throwable)
          case LogLevel.Error   =>
            if throwable eq null then logger.error(rendered) else logger.error(rendered, throwable)
          case LogLevel.Fatal   =>
            if throwable eq null then logger.error(rendered) else logger.error(rendered, throwable)
      }
    }
