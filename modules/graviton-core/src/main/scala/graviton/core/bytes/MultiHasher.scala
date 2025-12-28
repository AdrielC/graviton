package graviton.core.bytes

import zio.prelude.{NonEmptySortedMap, Validation}

final case class MultiHasher private (hashers: MultiHasher.Hashers):
  def update(chunk: Hasher.Digestable): MultiHasher =
    copy(hashers = MultiHasher.Hashers(hashers.mapValues(hasher => hasher.update(chunk))))

  def results: Validation[String, MultiHasher.Results] =
    Validation
      .validateAll(hashers.map((algo, hasher) => Validation.fromEither(hasher.digest.map(digest => (algo, digest)))))
      .flatMap(results => Validation.fromEither(MultiHasher.Results(results)))

object MultiHasher:

  opaque type Hashers <: NonEmptySortedMap[HashAlgo, Hasher] = NonEmptySortedMap[HashAlgo, Hasher]

  opaque type Results <: NonEmptySortedMap[HashAlgo, Digest] = NonEmptySortedMap[HashAlgo, Digest]

  object Results:
    def apply(iterable: Iterable[(HashAlgo, Digest)]): Either[String, Results] =
      NonEmptySortedMap
        .fromIterableOption(iterable)
        .toRight("Failed to validate all hashers")
        .map(MultiHasher.Results(_))

    def apply(results: NonEmptySortedMap[HashAlgo, Digest]): Results = results

  object Hashers:

    def apply(hashers: NonEmptySortedMap[HashAlgo, Hasher]): Hashers = hashers

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
