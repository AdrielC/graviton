package graviton.core.bytes

final case class MultiHasher private (hashers: List[Hasher]):
  def update(chunk: Array[Byte]): MultiHasher =
    hashers.foreach(_.update(chunk))
    this

  def results: List[Either[String, Digest]] = hashers.map(_.result)

object MultiHasher:
  def make(algorithms: HashAlgo*): Either[String, MultiHasher] =
    val initial: Either[String, List[Hasher]] = Right(Nil)
    val acc                                   = algorithms.toList.foldLeft(initial) { (acc, algo) =>
      for
        built  <- acc
        hasher <- Hasher.messageDigest(algo)
      yield hasher :: built
    }
    acc.map(list => MultiHasher(list.reverse))

  def unsafe(algorithms: HashAlgo*): MultiHasher =
    make(algorithms*).fold(msg => throw IllegalStateException(msg), identity)
