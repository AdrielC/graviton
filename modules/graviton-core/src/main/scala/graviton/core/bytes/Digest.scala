package graviton.core.bytes

final case class Digest private (algo: HashAlgo, value: String)

object Digest:
  private def validate(algo: HashAlgo, value: String): Either[String, String] =
    val normalized = value.toLowerCase
    val isHex      = normalized.forall(ch => ch.isDigit || (ch >= 'a' && ch <= 'f'))
    if !isHex then Left("Digest must be hexadecimal")
    else if normalized.length != algo.hexLength then Left(s"Expected ${algo.hexLength} hex chars for $algo")
    else Right(normalized)

  def make(algo: HashAlgo, value: String): Either[String, Digest] =
    validate(algo, value).map(valid => Digest(algo, valid))
