package graviton.core.model

import zio.test.*

object ByteConstraintsSpec extends ZIOSpecDefault:

  override def spec: Spec[TestEnvironment, Any] =
    suite("ByteConstraints")(
      test("BlockSize accepts values within bounds") {
        val result = ByteConstraints.refineBlockSize(ByteConstraints.MinBlockBytes + 1024)
        assertTrue(result.isRight)
      },
      test("BlockSize rejects values above the maximum") {
        val result = ByteConstraints.refineBlockSize(ByteConstraints.MaxBlockBytes + 1)
        assertTrue(result.isLeft)
      },
      test("FileSize enforces lower and upper bounds") {
        val within = ByteConstraints.refineFileSize(128L)
        val below  = ByteConstraints.refineFileSize(ByteConstraints.MinFileBytes - 1)
        val above  = ByteConstraints.refineFileSize(ByteConstraints.MaxFileBytes + 1)
        assertTrue(within.isRight && below.isLeft && above.isLeft)
      },
      test("ChunkCount disallows negatives") {
        val valid   = ByteConstraints.refineChunkCount(0L)
        val invalid = ByteConstraints.refineChunkCount(-1L)
        assertTrue(valid.isRight && invalid.isLeft)
      },
    )
