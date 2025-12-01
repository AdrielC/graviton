package graviton

import zio.*
import zio.test.*
import zio.stream.*
import graviton.core.model.Block

object HashingSpec extends ZIOSpecDefault:
  def spec = suite("HashingSpec")(
    test("compute blake3 and sha256 hashes") {
      val bytes =
        Bytes(ZStream.fromIterable("hello world".getBytes.toIndexedSeq))
      for
        sha <- Hashing.compute(bytes, HashAlgorithm.SHA256)
        bl  <- Hashing.compute(bytes, HashAlgorithm.Blake3)
      yield assertTrue(
        sha.bytes.head._2.toArray
          .map("%02x".format(_))
          .mkString == "b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9" &&
          bl.bytes.head._2.toArray
            .map("%02x".format(_))
            .mkString == "d74981efa70a0c880b8d8c1985d075dbcbf679b99a5f9914e5aaf96b831a9e24"
      )
    },
    test("rolling hash emits prefix digests") {
      val stream = Blocks(
        ZStream.fromChunks(
          Chunk(
            Block.applyUnsafe(Chunk.fromArray("ab".getBytes)),
            Block.applyUnsafe(Chunk.fromArray("cd".getBytes)),
          )
        )
      )
      for
        hashes         <- Hashing.rolling(stream, HashAlgorithm.SHA256).runCollect
        expectedFirst  <- Hashing.compute(
                            Bytes(ZStream.fromIterable("ab".getBytes.toIndexedSeq)),
                            HashAlgorithm.SHA256,
                          )
        expectedSecond <- Hashing.compute(
                            Bytes(ZStream.fromIterable("abcd".getBytes.toIndexedSeq)),
                            HashAlgorithm.SHA256,
                          )
      yield assertTrue(
        hashes.map(_.bytes) == Chunk(expectedFirst, expectedSecond)
      )
    },
    test("sink computes digest without buffering entire stream") {
      val data = Chunk.fromArray("hello world".getBytes)
      for
        dig      <- ZStream.fromChunk(data).run(Hashing.sink(HashAlgorithm.SHA256))
        expected <- Hashing.compute(Bytes(ZStream.fromChunk(data)), HashAlgorithm.SHA256)
      yield assertTrue(dig == Hash.SingleHash(HashAlgorithm.SHA256, expected.bytes.head._2))
    },
  )
