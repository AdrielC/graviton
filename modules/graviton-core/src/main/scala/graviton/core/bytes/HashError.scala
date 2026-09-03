package graviton.core.bytes

import zio.NonEmptyChunk

sealed trait HashError derives CanEqual:
  def message: String

object HashError:

  final case class AlgorithmUnavailable(algo: HashAlgo, detail: String) extends HashError:
    override def message: String = s"${algo.primaryName} is unavailable: $detail"

  final case class InvalidByteLength(actual: Int, minimum: Int, maximum: Int) extends HashError:
    override def message: String = s"Hash bytes must contain between $minimum and $maximum bytes, received $actual"

  final case class InvalidHex(value: String) extends HashError:
    override def message: String = s"Invalid hexadecimal hash '$value'"

  final case class AlgorithmLengthMismatch(algo: HashAlgo, actual: Int) extends HashError:
    override def message: String = s"${algo.primaryName} hashes must contain exactly ${algo.hashBytes} bytes, received $actual"

  final case class Multiple(errors: NonEmptyChunk[HashError]) extends HashError:
    override def message: String = errors.map(_.message).mkString(", ")

  final case class InvariantViolation(detail: String) extends HashError:
    override def message: String = detail
