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
      test("FileSize enforces non-negativity and backend limits") {
        val within       = ByteConstraints.refineFileSize(128L)
        val below        = ByteConstraints.refineFileSize(ByteConstraints.MinFileBytes - 1)
        val exceedsLimit = ByteConstraints.enforceFileLimit(2048L, 1024L)
        val withinLimit  = ByteConstraints.enforceFileLimit(512L, 1024L)
        assertTrue(within.isRight && below.isLeft && exceedsLimit.isLeft && withinLimit.isRight)
      },
      test("ChunkCount disallows negatives") {
        val valid   = ByteConstraints.refineChunkCount(0L)
        val invalid = ByteConstraints.refineChunkCount(-1L)
        assertTrue(valid.isRight && invalid.isLeft)
      },
    )
