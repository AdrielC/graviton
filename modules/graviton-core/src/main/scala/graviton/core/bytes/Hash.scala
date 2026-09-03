package graviton.core.bytes

import zio.Chunk
import zio.NonEmptyChunk
import zio.schema.{DeriveSchema, Schema}

/**
 * A self-describing hash. Construction validates the algorithm-specific byte
 * length in addition to the wider [[HashBytes]] refinement.
 */
final case class Hash private (algo: HashAlgo, bytes: HashBytes) derives CanEqual

object Hash:

  def make(algo: HashAlgo, bytes: HashBytes): Either[HashError, Hash] =
    if bytes.length == algo.hashBytes then Right(Hash(algo, bytes))
    else Left(HashError.AlgorithmLengthMismatch(algo, bytes.length))

  private[bytes] def fromJdkBytes(algo: HashAlgo, value: Array[Byte]): Either[HashError, Hash] =
    HashBytes.fromChunk(Chunk.fromArray(value)).flatMap(make(algo, _))

  def apply[A: Hashable](algo: HashAlgo)(value: A): Either[HashError, Hash] =
    Hasher.make(algo).flatMap(_.update(value).hash)

  /**
   * Hash with one or more algorithms while retaining the precise legal result
   * shapes: one [[Hash]], or a strict non-empty chunk for multiple algorithms.
   */
  def compute[A: Hashable](algorithm: HashAlgo, algorithms: HashAlgo*)(value: A): Either[HashError, HashResult] =
    if algorithms.isEmpty then apply(algorithm)(value)
    else
      MultiHasher
        .makeTyped(algorithm, algorithms*)
        .flatMap(_.update(value).hashes.toEither.left.map(HashError.Multiple.apply))

  given Schema[Hash] = DeriveSchema.gen[Hash]

/** The two legal result shapes exposed by single and multi-algorithm hashing. */
type HashResult = Hash | NonEmptyChunk[Hash]
