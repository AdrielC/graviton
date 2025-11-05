package graviton.core.bytes

trait Hasher:
  def algo: HashAlgo
  def update(chunk: Array[Byte]): Hasher
  def result: Either[String, Digest]

final class MemoryHasher(val algo: HashAlgo) extends Hasher:
  private var state                               = Vector.empty[Byte]
  override def update(chunk: Array[Byte]): Hasher =
    state = state ++ chunk.toVector
    this
  override def result: Either[String, Digest]     =
    val hex = state.map(b => f"${b & 0xff}%02x").mkString
    Digest.make(algo, hex.padTo(algo.hexLength, '0'))

object Hasher:
  def memory(algo: HashAlgo): Hasher = new MemoryHasher(algo)
