package graviton.core.model

import graviton.core.types.*
import zio.*
import zio.test.*

/**
 * Boundary-value tests for all refined types in `graviton.core.types`.
 *
 * For each type we verify:
 *   - Min value is accepted
 *   - Max value is accepted
 *   - One below min is rejected
 *   - One above max is rejected
 *   - Zero / One constants (where applicable)
 */
object RefinedTypeBoundarySpec extends ZIOSpecDefault:

  def spec = suite("Refined type boundaries")(
    // --- Numeric size types --------------------------------------------------

    suite("BlockSize (1 .. 16_777_216)")(
      test("accepts min (1)")(assertTrue(BlockSize.either(1).isRight)),
      test("accepts max (16 MiB)")(assertTrue(BlockSize.either(16 * 1024 * 1024).isRight)),
      test("rejects 0")(assertTrue(BlockSize.either(0).isLeft)),
      test("rejects max+1")(assertTrue(BlockSize.either(16 * 1024 * 1024 + 1).isLeft)),
      test("Min == 1")(assertTrue(BlockSize.Min.value == 1)),
      test("Max == 16_777_216")(assertTrue(BlockSize.Max.value == 16 * 1024 * 1024)),
    ),
    suite("UploadChunkSize (1 .. 16_777_216)")(
      test("accepts min (1)")(assertTrue(UploadChunkSize.either(1).isRight)),
      test("accepts max (16 MiB)")(assertTrue(UploadChunkSize.either(16 * 1024 * 1024).isRight)),
      test("rejects 0")(assertTrue(UploadChunkSize.either(0).isLeft)),
      test("rejects max+1")(assertTrue(UploadChunkSize.either(16 * 1024 * 1024 + 1).isLeft)),
    ),
    suite("FileSize (1 .. 1_099_511_627_776)")(
      test("accepts 1")(assertTrue(FileSize.either(1L).isRight)),
      test("accepts 1 TiB")(assertTrue(FileSize.either(1099511627776L).isRight)),
      test("rejects 0")(assertTrue(FileSize.either(0L).isLeft)),
      test("rejects 1 TiB + 1")(assertTrue(FileSize.either(1099511627776L + 1L).isLeft)),
    ),
    suite("Size (1 .. Int.MaxValue)")(
      test("accepts 1")(assertTrue(Size.either(1).isRight)),
      test("accepts Int.MaxValue")(assertTrue(Size.either(Int.MaxValue).isRight)),
      test("rejects 0")(assertTrue(Size.either(0).isLeft)),
    ),
    suite("BlockIndex (0 .. Long.MaxValue)")(
      test("accepts 0")(assertTrue(BlockIndex.either(0L).isRight)),
      test("accepts Long.MaxValue")(assertTrue(BlockIndex.either(Long.MaxValue).isRight)),
      test("rejects -1")(assertTrue(BlockIndex.either(-1L).isLeft)),
    ),
    suite("Offset (0 .. Long.MaxValue)")(
      test("accepts 0")(assertTrue(Offset.either(0L).isRight)),
      test("accepts Long.MaxValue")(assertTrue(Offset.either(Long.MaxValue).isRight)),
      test("rejects -1")(assertTrue(Offset.either(-1L).isLeft)),
    ),
    suite("BlobOffset")(
      test("accepts 0")(assertTrue(BlobOffset.either(0L).isRight)),
      test("rejects -1")(assertTrue(BlobOffset.either(-1L).isLeft)),
    ),
    suite("CompressionLevel (-1 .. 22)")(
      test("accepts -1")(assertTrue(CompressionLevel.either(-1).isRight)),
      test("accepts 0")(assertTrue(CompressionLevel.either(0).isRight)),
      test("accepts 22")(assertTrue(CompressionLevel.either(22).isRight)),
      test("rejects -2")(assertTrue(CompressionLevel.either(-2).isLeft)),
      test("rejects 23")(assertTrue(CompressionLevel.either(23).isLeft)),
    ),

    // --- String types --------------------------------------------------------

    suite("Algo")(
      test("accepts sha-256")(assertTrue(Algo.either("sha-256").isRight)),
      test("accepts blake3")(assertTrue(Algo.either("blake3").isRight)),
      test("accepts sha-1")(assertTrue(Algo.either("sha-1").isRight)),
      test("accepts md5")(assertTrue(Algo.either("md5").isRight)),
      test("rejects unknown")(assertTrue(Algo.either("sha-512").isLeft)),
      test("rejects empty")(assertTrue(Algo.either("").isLeft)),
    ),
    suite("HexLower")(
      test("accepts valid hex")(assertTrue(HexLower.either("0123456789abcdef").isRight)),
      test("rejects uppercase")(assertTrue(HexLower.either("0123ABCDEF").isLeft)),
      test("rejects empty")(assertTrue(HexLower.either("").isLeft)),
    ),
    suite("HexUpper")(
      test("accepts valid hex")(assertTrue(HexUpper.either("0123456789ABCDEF").isRight)),
      test("rejects lowercase")(assertTrue(HexUpper.either("0123abcdef").isLeft)),
    ),
    suite("Identifier")(
      test("accepts alphanumeric start")(assertTrue(Identifier.either("a").isRight)),
      test("accepts dots and dashes")(assertTrue(Identifier.either("graviton.chunk-count").isRight)),
      test("rejects empty")(assertTrue(Identifier.either("").isLeft)),
      test("rejects starting with dot")(assertTrue(Identifier.either(".bad").isLeft)),
    ),
    suite("LocatorScheme")(
      test("accepts 'cas'")(assertTrue(LocatorScheme.either("cas").isRight)),
      test("accepts 's3+https'")(assertTrue(LocatorScheme.either("s3+https").isRight)),
      test("rejects empty")(assertTrue(LocatorScheme.either("").isLeft)),
      test("rejects uppercase")(assertTrue(LocatorScheme.either("CAS").isLeft)),
    ),
    suite("LocatorBucket")(
      test("accepts simple name")(assertTrue(LocatorBucket.either("my-bucket").isRight)),
      test("rejects empty")(assertTrue(LocatorBucket.either("").isLeft)),
      test("rejects slash")(assertTrue(LocatorBucket.either("has/slash").isLeft)),
    ),
    suite("LocatorPath")(
      test("accepts path")(assertTrue(LocatorPath.either("cas/blocks/sha256/abc").isRight)),
      test("rejects empty")(assertTrue(LocatorPath.either("").isLeft)),
      test("rejects whitespace")(assertTrue(LocatorPath.either("has space").isLeft)),
    ),
    suite("Mime")(
      test("accepts valid")(assertTrue(Mime.either("application/pdf").isRight)),
      test("rejects empty")(assertTrue(Mime.either("").isLeft)),
    ),
    suite("CustomAttributeName")(
      test("accepts valid")(assertTrue(CustomAttributeName.either("myattr").isRight)),
      test("rejects empty")(assertTrue(CustomAttributeName.either("").isLeft)),
    ),

    // --- Block-level refinement -----------------------------------------------

    suite("Block (Chunk[Byte])")(
      test("accepts 1-byte chunk") {
        assertTrue(Block.fromChunk(Chunk(0.toByte)).isRight)
      },
      test("accepts max-size chunk") {
        assertTrue(Block.fromChunk(Chunk.fill(16 * 1024 * 1024)(0.toByte)).isRight)
      },
      test("rejects empty chunk") {
        assertTrue(Block.fromChunk(Chunk.empty).isLeft)
      },
    ),
  )
