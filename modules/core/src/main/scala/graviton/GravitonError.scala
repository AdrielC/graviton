package graviton

sealed trait GravitonError extends Throwable
object GravitonError:
  final case class NotFound(msg: String)           extends Exception(msg) with GravitonError
  final case class BackendUnavailable(msg: String) extends Exception(msg) with GravitonError
  final case class CorruptData(msg: String)        extends Exception(msg) with GravitonError
  final case class PolicyViolation(msg: String)    extends Exception(msg) with GravitonError
  final case class ChunkerFailure(msg: String)     extends Exception(msg) with GravitonError
