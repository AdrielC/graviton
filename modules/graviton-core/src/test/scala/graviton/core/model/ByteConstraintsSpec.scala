package graviton.core.model

import graviton.core.types.{BlockSize, ChunkCount, FileSize}
import zio.test.*

object ByteConstraintsSpec extends ZIOSpecDefault:

  override def spec: Spec[TestEnvironment, Any] =
    suite("ByteConstraints")(
      test("BlockSize accepts values within bounds") {
        val result = BlockSize.either(ByteConstraints.MinBlockBytes + 1024)
        assertTrue(result.isRight)
      },
      test("BlockSize rejects values above the maximum") {
        val result = BlockSize.either(ByteConstraints.MaxBlockBytes + 1)
        assertTrue(result.isLeft)
      },
      test("FileSize enforces positivity and backend limits") {
        val within       = FileSize.either(128L)
        val below        = FileSize.either(ByteConstraints.MinFileBytes - 1)
        val exceedsLimit = ByteConstraints.enforceFileLimit(2048L, 1024L)
        val withinLimit  = ByteConstraints.enforceFileLimit(512L, 1024L)
        assertTrue(within.isRight && below.isLeft && exceedsLimit.isLeft && withinLimit.isRight)
      },
      test("ChunkCount must be positive") {
        val valid   = ChunkCount.either(1L)
        val invalid = ChunkCount.either(0L)
        assertTrue(valid.isRight && invalid.isLeft)
      },
    )
