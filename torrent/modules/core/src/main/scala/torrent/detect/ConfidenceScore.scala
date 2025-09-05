package torrent.detect

sealed trait ConfidenceScore

object ConfidenceScore {
  case object High    extends ConfidenceScore
  case object Medium  extends ConfidenceScore
  case object Low     extends ConfidenceScore
  case object Unknown extends ConfidenceScore
}

case class DetectionResult(contentType: ContentType, confidence: ConfidenceScore)
