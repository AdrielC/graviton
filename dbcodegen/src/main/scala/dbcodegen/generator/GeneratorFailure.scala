package dbcodegen.generator

import java.nio.file.Path

/** Generic failure wrapper for generator-style pipelines (mirrors the spirit of
  * `io.github.vigoo.metagen.core.GeneratorFailure` used by ZIO codegen repos).
  */
sealed trait GeneratorFailure[+E] extends Product with Serializable:
  def pretty: String

object GeneratorFailure:
  final case class UserError[E](error: E, context: Option[String] = None) extends GeneratorFailure[E]:
    override def pretty: String =
      context match
        case Some(ctx) => s"$ctx: $error"
        case None      => error.toString

  final case class IOError(path: Path, message: String, cause: Option[Throwable] = None) extends GeneratorFailure[Nothing]:
    override def pretty: String =
      cause match
        case Some(t) => s"$message ($path): ${t.getMessage}"
        case None    => s"$message ($path)"

