package graviton.testkit

import zio.*
import zio.test.*
import zio.Chunk

object TestGen {
  val byte: Gen[Any, Byte] = Gen.byte
  
  val bytes: Gen[Any, Chunk[Byte]] = 
    Gen.listOf(byte).map(l => Chunk.fromIterable(l))
  
  val boundedBytes: Gen[Any, Chunk[Byte]] =
    Gen.int(0, 1 << 16).flatMap(n => Gen.listOfN(n)(byte)).map(Chunk.fromIterable)

  def chunkedBytes(chunkMin: Int, chunkMax: Int): Gen[Any, Chunk[Byte]] =
    for {
      total <- Gen.int(0, 1 << 18)
      chunks <- Gen.listOfBounded(0, total / math.max(chunkMin, 1) + 1)(
        Gen.int(chunkMin max 1, chunkMax max 1).map(_.min(total max 0))
      )
      bytes <- Gen.listOfN(chunks.sum)(byte)
    } yield Chunk.fromIterable(bytes)

  val sizeSplit: Gen[Any, List[Int]] =
    for {
      total <- Gen.int(0, 1 << 18)
      parts <- Gen.listOf(Gen.int(1, 1 << 16))
    } yield parts
  
  /** Generate random byte chunks up to 1MB for fuzzing */
  val largeBytes: Gen[Any, Chunk[Byte]] =
    Gen.int(0, 1 << 20).flatMap(n => Gen.listOfN(n)(byte)).map(Chunk.fromIterable)
  
  /** Generate byte sequences with specific patterns for testing CDC boundaries */
  def repeatingPattern(pattern: Array[Byte], count: Int): Gen[Any, Chunk[Byte]] =
    Gen.const(Chunk.fromArray(Array.fill(count)(pattern).flatten))
  
  /** Generate Either values for choice/branch testing */
  def eitherBytes: Gen[Any, Chunk[Either[Byte, Byte]]] =
    bytes.map(_.map(b => if ((b & 1) == 0) Left(b) else Right(b)))
  
  /** Generate paired byte values for parallel product testing */
  def pairedBytes: Gen[Any, Chunk[(Byte, Byte)]] =
    for {
      a <- bytes
      b <- bytes
      minLen = math.min(a.length, b.length)
    } yield a.take(minLen).zip(b.take(minLen))
}
