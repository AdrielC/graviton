package graviton

import zio.*
import zio.test.*
import zio.stream.*

object HashingSpec extends ZIOSpecDefault:
  def spec = suite("HashingSpec")(
    test("compute blake3 and sha256 hashes") {
      val bytes = ZStream.fromIterable("hello world".getBytes.toIndexedSeq)
      for
        sha <- Hashing.compute(bytes, HashAlgorithm.SHA256)
        bl <- Hashing.compute(bytes, HashAlgorithm.Blake3)
      yield assertTrue(
        sha.toArray
          .map("%02x".format(_))
          .mkString == "b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9" &&
          bl.toArray
            .map("%02x".format(_))
            .mkString == "d74981efa70a0c880b8d8c1985d075dbcbf679b99a5f9914e5aaf96b831a9e24"
      )
    },
    test("rolling hash emits prefix digests") {
      val stream = ZStream.fromChunks(
        Chunk.fromArray("ab".getBytes),
        Chunk.fromArray("cd".getBytes)
      )
      for
        hashes <- Hashing.rolling(stream, HashAlgorithm.SHA256).runCollect
        expectedFirst <- Hashing.compute(
          ZStream.fromIterable("ab".getBytes.toIndexedSeq),
          HashAlgorithm.SHA256
        )
        expectedSecond <- Hashing.compute(
          ZStream.fromIterable("abcd".getBytes.toIndexedSeq),
          HashAlgorithm.SHA256
        )
      yield assertTrue(
        hashes.map(_.bytes) == Chunk(expectedFirst, expectedSecond)
      )
    }
  )
