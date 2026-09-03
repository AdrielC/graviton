package graviton.runtime.stores

import graviton.core.bytes.{HashAlgo, HashBytes, Hasher}
import graviton.core.bytes.Digest.*
import graviton.core.keys.BinaryKey
import graviton.core.macros.Interpolators.*
import graviton.core.types.{BlobOffset, BlockCount, BlockIndex, FileSize}
import zio.{Chunk, IO, ZIO}
import zio.test.*

object ManifestMerkleTreeSpec extends ZIOSpecDefault:

  override def spec: Spec[Any, Any] =
    suite("ManifestMerkleTree")(
      test("is deterministic across multiple leaf and branch levels") {
        for
          first  <- build(entryCount = 4225)
          second <- build(entryCount = 4225)
        yield assertTrue(first._1 == second._1)
      },
      test("keeps the version 1 root encoding stable") {
        build(entryCount = 1).map { result =>
          assertTrue(result._1.hex == hex"d15b806fe24fc8e040c774b2e242f4251c7008e74589b982bdbd3c45c80a6912")
        }
      },
      test("changes the root when one leaf key changes") {
        for
          original <- build(entryCount = 130)
          changed  <- build(entryCount = 130, changedIndex = Some(65L))
        yield assertTrue(original._1 != changed._1)
      },
      test("binds the versioned root prefix") {
        for
          original <- build(entryCount = 130, prefix = "blob-a")
          changed  <- build(entryCount = 130, prefix = "blob-b")
        yield assertTrue(original._1 != changed._1)
      },
      test("keeps a bounded frontier instead of retaining all entries") {
        build(entryCount = 4225).map { result =>
          assertTrue(
            result._2 <= ManifestMerkleTree.Fanout * 4,
            result._2 < 4225,
          )
        }
      },
      test("rejects non-contiguous byte ranges") {
        (for
          hasher  <- fromEither(Hasher.make(HashAlgo.Sha256).left.map(_.message))
          builder <- builder(hasher)
          key     <- block(1)
          index   <- fromEither(BlockIndex.either(0L))
          offset  <- fromEither(BlobOffset.either(1L))
          _       <- builder.add(index, key, offset).mapError(_.message)
        yield ()).exit.map(exit => assertTrue(exit.isFailure))
      },
      test("serializes duplicate concurrent additions without corrupting the frontier") {
        for
          hasher   <- fromEither(Hasher.make(HashAlgo.Sha256).left.map(_.message))
          tree     <- builder(hasher)
          key      <- block(1)
          index    <- fromEither(BlockIndex.either(0L))
          offset   <- fromEither(BlobOffset.either(0L))
          results  <- ZIO.collectAllPar(Chunk.fill(2)(tree.add(index, key, offset).either))
          observed <- tree.entryCount
        yield assertTrue(results.count(_.isRight) == 1, results.count(_.isLeft) == 1, observed == 1)
      },
      test("rejects the retired flat-proof version") {
        for
          built <- build(entryCount = 1)
          keyId <- fromEither(ManifestKeyId.either("test-key"))
          result = ManifestProof.make(
                     version = 2,
                     keyId = keyId,
                     merkleRoot = built._1,
                     signature = Chunk.fill(ManifestProof.SignatureBytes)(0.toByte),
                   )
        yield assertTrue(result.isLeft)
      },
    )

  private def build(
    entryCount: Int,
    changedIndex: Option[Long] = None,
    prefix: String = "blob",
  ): IO[String, (HashBytes, Int)] =
    for
      count      <- fromEither(BlockCount.either(entryCount))
      size       <- fromEither(FileSize.either(entryCount.toLong))
      hasher     <- fromEither(Hasher.make(HashAlgo.Sha256).left.map(_.message))
      tree       <- builder(hasher)
      regularKey <- block(1)
      changedKey <- block(2)
      _          <- ZIO.foreachDiscard(0 until entryCount) { index =>
                      val key = if changedIndex.contains(index.toLong) then changedKey else regularKey
                      for
                        blockIndex <- fromEither(BlockIndex.either(index.toLong))
                        offset     <- fromEither(BlobOffset.either(index.toLong))
                        _          <- tree.add(blockIndex, key, offset).mapError(_.message)
                      yield ()
                    }
      root       <- tree.finish(ManifestMerkleTree.text(prefix), count, size).mapError(_.message)
      peak       <- tree.peakFrontierNodes
    yield root -> peak

  private def builder(hasher: Hasher): IO[String, ManifestMerkleTree.Builder] =
    ManifestMerkleTree.Builder.make(hasher).mapError(_.message)

  private def block(value: Byte): IO[String, BinaryKey.Block] =
    fromEither(HashAlgo.Sha256(Chunk.single(value)).flatMap(BinaryKey.block))

  private def fromEither[A](value: Either[String, A]): IO[String, A] =
    ZIO.fromEither(value)
