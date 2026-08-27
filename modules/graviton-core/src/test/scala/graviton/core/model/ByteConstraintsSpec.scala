package graviton.core.model

import graviton.core.types.{BlockSize, ChunkCount, FileSize, SizeLongSubtype, SizeSubtype}
import zio.test.*

object ByteConstraintsSpec extends ZIOSpecDefault:

  object LegacyBlockSize extends SizeSubtype.Trait[1, 1024, 0, 1]
  object LegacyByteCount extends SizeLongSubtype.Trait[1L, 4096L, 0L, 1L]
  object WideByteCount   extends SizeLongSubtype.Trait[1L, Long.MaxValue.type, 0L, 1L]

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
      test("legacy SizeSubtype.Trait source extensions remain supported") {
        val valid       = LegacyBlockSize.either(1024)
        val invalid     = LegacyBlockSize.either(1025)
        val longValid   = LegacyByteCount.either(4096L)
        val longInvalid = LegacyByteCount.either(4097L)
        assertTrue(valid.isRight && invalid.isLeft && longValid.isRight && longInvalid.isLeft)
      },
      test("checked Long multiplication rejects overflow before refinement") {
        // (2^32 + 1)^2 wraps to 2^33 + 1 in a Long, which is otherwise inside this refinement.
        val result = WideByteCount.either(4294967297L).flatMap(value => value.checkedMul(value))
        assertTrue(result.isLeft)
      },
    )
