package graviton.core.bytes

import graviton.core.types.{Algo, HexLower}
import zio.{Chunk, NonEmptyChunk}

import java.security.{MessageDigest, NoSuchAlgorithmException}
import scala.util.Try

/**
 * A self-describing transfer checksum. Unlike [[Hash]], it is never a CAS
 * identity. `Algo("md5")` is valid here while MD5 remains absent from
 * [[HashAlgo]].
 */
final case class Checksum private (algo: Algo, bytes: Digest) derives CanEqual

object Checksum:

  def make(algo: Algo, bytes: Digest): Either[ChecksumError, Checksum] =
    graviton.core.types
      .validateDigest(algo, bytes.hex)
      .left
      .map(reason => ChecksumError.InvalidByteLength(algo, bytes.length, reason))
      .map(_ => Checksum(algo, bytes))

  def parse(algo: Algo, value: HexLower): Either[ChecksumError, Checksum] =
    for
      _     <- graviton.core.types.validateDigest(algo, value).left.map(ChecksumError.InvalidEncoding(algo, _))
      bytes <- Digest.fromString(value.value).left.map(ChecksumError.InvalidEncoding(algo, _))
      value <- make(algo, bytes)
    yield value

sealed trait ChecksumError derives CanEqual:
  def message: String

object ChecksumError:

  final case class UnsupportedAlgorithm(name: String) extends ChecksumError:
    def message: String = s"Unsupported checksum algorithm '$name'"

  final case class AlgorithmUnavailable(algo: Algo, detail: String) extends ChecksumError:
    def message: String = s"Checksum algorithm '${algo.value}' is unavailable: $detail"

  final case class InvalidEncoding(algo: Algo, detail: String) extends ChecksumError:
    def message: String = s"Invalid ${algo.value} checksum: $detail"

  final case class InvalidByteLength(algo: Algo, actual: Int, detail: String) extends ChecksumError:
    def message: String = s"Invalid ${algo.value} checksum length ($actual bytes): $detail"

  final case class Mismatch(algo: Algo, expected: HexLower, actual: HexLower) extends ChecksumError:
    def message: String = s"${algo.value} checksum mismatch: expected ${expected.value}, computed ${actual.value}"

  final case class InvalidState(detail: String) extends ChecksumError:
    def message: String = detail

  final case class Multiple(errors: NonEmptyChunk[ChecksumError]) extends ChecksumError:
    def message: String = errors.map(_.message).mkString("; ")

/**
 * Incremental verifier for optional uploader-provided checksums.
 *
 * Inputs remain segmented [[HashInput]] values. The only array conversion is private JCA
 * interop, and callers cannot use the verifier to create a CAS key.
 */
final class ChecksumVerifier private (
  entries: Chunk[ChecksumVerifier.Entry],
  private var finished: Boolean,
):

  def update[A: Hashable](value: A): Either[ChecksumError, Unit] =
    if finished then Left(ChecksumError.InvalidState("Checksum verifier is already finished"))
    else
      Hashable[A].input(value).foreachSegment(segment => entries.foreach(_.engine.update(segment.bytes)))
      Right(())

  def verify: Either[ChecksumError, Map[Algo, HexLower]] =
    if finished then Left(ChecksumError.InvalidState("Checksum verifier is already finished"))
    else
      finished = true
      val checked = entries.map { entry =>
        entry.engine.finish.flatMap { actual =>
          val actualHex = actual.bytes.hex
          Either.cond(
            MessageDigest.isEqual(entry.expected.bytes.toInteropArray, actual.bytes.toInteropArray),
            entry.expected.algo -> actualHex,
            ChecksumError.Mismatch(entry.expected.algo, entry.expected.bytes.hex, actualHex),
          )
        }
      }
      NonEmptyChunk.fromChunk(checked.collect { case Left(error) => error }) match
        case Some(errors) => Left(ChecksumError.Multiple(errors))
        case None         => Right(checked.collect { case Right(result) => result }.toMap)

object ChecksumVerifier:

  private val Md5 = Algo("md5")

  private final case class Entry(expected: Checksum, engine: Engine)

  private sealed trait Engine:
    def update(bytes: Chunk[Byte]): Unit
    def finish: Either[ChecksumError, Checksum]

  private final class HashEngine(algo: Algo, hasher: Hasher) extends Engine:
    def update(bytes: Chunk[Byte]): Unit =
      val _ = hasher.update(bytes)

    def finish: Either[ChecksumError, Checksum] =
      hasher.hash.left
        .map(error => ChecksumError.AlgorithmUnavailable(algo, error.message))
        .flatMap(hash => Checksum.make(algo, hash.bytes))

  private final class Md5Engine(algo: Algo, digest: MessageDigest) extends Engine:
    def update(bytes: Chunk[Byte]): Unit =
      // MessageDigest is the private JCA boundary. The array cannot escape.
      digest.update(bytes.toArray)

    def finish: Either[ChecksumError, Checksum] =
      Digest
        .fromArrayCopy(digest.digest())
        .left
        .map(reason => ChecksumError.InvalidEncoding(algo, reason))
        .flatMap(Checksum.make(algo, _))

  def make(expected: Map[Algo, HexLower]): Either[ChecksumError, ChecksumVerifier] =
    val built = expected.toVector.sortBy(_._1.value).map { case (algo, expectedHex) =>
      for
        checksum <- Checksum.parse(algo, expectedHex)
        engine   <- makeEngine(algo)
      yield Entry(checksum, engine)
    }
    NonEmptyChunk.fromIterableOption(built.collect { case Left(error) => error }) match
      case Some(errors) => Left(ChecksumError.Multiple(errors))
      case None         => Right(new ChecksumVerifier(Chunk.fromIterable(built.collect { case Right(entry) => entry }), false))

  private def makeEngine(algo: Algo): Either[ChecksumError, Engine] =
    HashAlgo.fromString(algo.value) match
      case Some(hashAlgorithm) =>
        Hasher
          .make(hashAlgorithm)
          .left
          .map(error => ChecksumError.AlgorithmUnavailable(algo, error.message))
          .map(new HashEngine(algo, _))
      case None if algo == Md5 =>
        Try(MessageDigest.getInstance("MD5")).toEither.left
          .map {
            case error: NoSuchAlgorithmException => ChecksumError.AlgorithmUnavailable(algo, error.getMessage)
            case error                           => ChecksumError.AlgorithmUnavailable(algo, error.toString)
          }
          .map(new Md5Engine(algo, _))
      case None                => Left(ChecksumError.UnsupportedAlgorithm(algo.value))
