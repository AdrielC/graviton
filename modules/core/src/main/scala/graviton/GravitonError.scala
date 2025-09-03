package graviton

enum GravitonError(msg: String) extends Exception(msg):
    
    case NotFound(msg: String)
        extends GravitonError(msg)

    case BackendUnavailable(msg: String)
        extends GravitonError(msg)

    case CorruptData(msg: String)
        extends GravitonError(msg)

    case PolicyViolation(msg: String)
        extends GravitonError(msg)