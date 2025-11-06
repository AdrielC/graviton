package graviton

enum GravitonError(msg: String, cause: Option[Throwable] = None) extends Exception(cause.fold(msg)(e => s"$msg: ${e.getMessage}")):
  case NotFound(msg: String, cause: Option[Throwable] = None)           extends GravitonError(msg, cause)
  case BackendUnavailable(msg: String, cause: Option[Throwable] = None) extends GravitonError(msg, cause)
  case CorruptData(msg: String, cause: Option[Throwable] = None)        extends GravitonError(msg, cause)
  case PolicyViolation(msg: String, cause: Option[Throwable] = None)    extends GravitonError(msg, cause)
  case ChunkerFailure(msg: String, cause: Option[Throwable] = None)     extends GravitonError(msg, cause)
