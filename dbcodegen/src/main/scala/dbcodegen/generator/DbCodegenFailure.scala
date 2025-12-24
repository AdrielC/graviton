package dbcodegen.generator

/** Domain failures for dbcodegen, kept small and descriptive so they can be wrapped
  * by `GeneratorFailure` for ZIO-style pipelines.
  */
sealed trait DbCodegenFailure extends Product with Serializable:
  def message: String

object DbCodegenFailure:
  final case class InvalidConfig(message: String) extends DbCodegenFailure
  final case class IntrospectionFailed(message: String, cause: Option[Throwable] = None) extends DbCodegenFailure
  final case class RenderFailed(message: String) extends DbCodegenFailure

