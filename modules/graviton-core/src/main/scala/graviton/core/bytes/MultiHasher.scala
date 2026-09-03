package graviton.core.bytes

import zio.NonEmptyChunk
import zio.prelude.{NonEmptySortedMap, Validation}
import scala.annotation.targetName

final case class MultiHasher private (hashers: MultiHasher.Hashers):
  @targetName("updateHashable")
  def update[A: Hashable](value: A): MultiHasher =
    copy(hashers = MultiHasher.Hashers.fromPairs(hashers.map((algo, hasher) => algo -> hasher.update(value))))

  @targetName("update")
  def updateLegacy(value: Hasher.Digestable): MultiHasher =
    copy(hashers = MultiHasher.Hashers.fromPairs(hashers.map((algo, hasher) => algo -> hasher.updateLegacy(value))))

  def hashes: Validation[HashError, MultiHasher.Hashes] =
    Validation
      .validateAll(hashers.map((_, hasher) => Validation.fromEither(hasher.hash)))
      .flatMap(results =>
        Validation.fromEither(
          NonEmptyChunk
            .fromIterableOption(results)
            .toRight(HashError.InvariantViolation("MultiHasher cannot lose its final algorithm"))
            .map(MultiHasher.Hashes(_))
        )
      )

  def results: Validation[String, MultiHasher.Results] =
    Validation
      .validateAll(hashers.map((algo, hasher) => Validation.fromEither(hasher.digest.map(digest => (algo, digest)))))
      .flatMap(results => Validation.fromEither(MultiHasher.Results(results)))

object MultiHasher:

  opaque type Hashers <: NonEmptySortedMap[HashAlgo, Hasher] = NonEmptySortedMap[HashAlgo, Hasher]

  opaque type Results <: NonEmptySortedMap[HashAlgo, Digest] = NonEmptySortedMap[HashAlgo, Digest]

  opaque type Hashes <: NonEmptyChunk[Hash] = NonEmptyChunk[Hash]

  object Hashes:
    def apply(results: NonEmptyChunk[Hash]): Hashes = results

  object Results:
    def apply(iterable: Iterable[(HashAlgo, Digest)]): Either[String, Results] =
      NonEmptySortedMap
        .fromIterableOption(iterable)
        .toRight("Failed to validate all hashers")
        .map(MultiHasher.Results(_))

    def apply(results: NonEmptySortedMap[HashAlgo, Digest]): Results = results

  object Hashers:

    def apply(hashers: NonEmptySortedMap[HashAlgo, Hasher]): Hashers = hashers

    private[bytes] def fromPairs(pairs: Iterable[(HashAlgo, Hasher)]): Hashers =
      NonEmptySortedMap
        .fromIterableOption(pairs)
        .map(MultiHasher.Hashers(_))
        .getOrElse(throw new IllegalStateException("MultiHasher cannot lose its final algorithm"))

    def apply(algo: (HashAlgo, Hasher), algos: (HashAlgo, Hasher)*): Hashers =
      NonEmptySortedMap(algo, algos*)

    def default: Either[String, MultiHasher] =
      val validated =
        Validation
          .validateAll(
            HashAlgo.preferredOrder
              .map(algo => Validation.fromEither(algo.hasher(None).map(hasher => (algo, hasher))))
          )
          .toEither
          .left
          .map(_.mkString(", "))

      validated
        .map { pairs =>
          val hashers: NonEmptySortedMap[HashAlgo, Hasher] =
            NonEmptySortedMap.fromNonEmptyChunk(pairs)
          MultiHasher(MultiHasher.Hashers(hashers))
        }

  def make(algorithm: HashAlgo, algorithms: HashAlgo*): Either[String, MultiHasher] =
    val acc = algorithms.foldLeft(algorithm.hasher(None).map(hasher => Hashers(algorithm -> hasher))) { (acc, algo) =>
      acc.flatMap(results => algo.hasher(None).map(hasher => results.add(algo, hasher)))
    }
    acc.map(MultiHasher(_))

  def makeTyped(algorithm: HashAlgo, algorithms: HashAlgo*): Either[HashError, MultiHasher] =
    val acc = algorithms.foldLeft(Hasher.make(algorithm).map(hasher => Hashers(algorithm -> hasher))) { (acc, algo) =>
      acc.flatMap(results => Hasher.make(algo).map(hasher => results.add(algo, hasher)))
    }
    acc.map(MultiHasher(_))
