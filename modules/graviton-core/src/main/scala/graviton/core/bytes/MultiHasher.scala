package graviton.core.bytes

final case class MultiHasher(hashers: List[Hasher]):
  def update(chunk: Array[Byte]): MultiHasher =
    hashers.foreach(_.update(chunk))
    this
  def results: List[Either[String, Digest]]   = hashers.map(_.result)

object MultiHasher:
  def apply(algorithms: HashAlgo*): MultiHasher =
    MultiHasher(algorithms.toList.map(Hasher.memory))
