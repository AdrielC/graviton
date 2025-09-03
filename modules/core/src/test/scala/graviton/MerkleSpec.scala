package graviton

import zio.*
import zio.stream.*
import zio.test.*

object MerkleSpec extends ZIOSpecDefault:
  def spec = suite("MerkleSpec")(
    test("computes merkle root using sha256") {
      val data = ZStream.fromIterable("abcdefgh".getBytes.toIndexedSeq)
      for
        root <- Merkle.root(data, chunkSize = 4, HashAlgorithm.SHA256)
      yield assertTrue(
        root.toArray.map("%02x".format(_)).mkString ==
          "7d5473712172f9ec1494baa03da3d8734d12d385d1ca6340856771c3d93382e6"
      )
    }
  )

