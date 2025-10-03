package graviton.ranges

import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.numeric

object Ranges:
  type Offset    = Long :| numeric.GreaterEqual[0]
  type Length    = Long :| numeric.GreaterEqual[0]
  type ByteIndex = Long :| numeric.GreaterEqual[0]

  type RangeStart = Long :| numeric.GreaterEqual[0]
  type RangeEnd   = Long :| numeric.GreaterEqual[0]

  object Offset:
    def from(value: Long): Either[String, Offset] = value.refineEither[numeric.GreaterEqual[0]]
    def unsafe(value: Long): Offset               = value.refineUnsafe[numeric.GreaterEqual[0]]

  object Length:
    def from(value: Long): Either[String, Length] = value.refineEither[numeric.GreaterEqual[0]]
    def unsafe(value: Long): Length               = value.refineUnsafe[numeric.GreaterEqual[0]]

  object ByteIndex:
    def from(value: Long): Either[String, ByteIndex] = value.refineEither[numeric.GreaterEqual[0]]
    def unsafe(value: Long): ByteIndex               = value.refineUnsafe[numeric.GreaterEqual[0]]

  object RangeStart:
    def from(value: Long): Either[String, RangeStart] = value.refineEither[numeric.GreaterEqual[0]]
    def unsafe(value: Long): RangeStart               = value.refineUnsafe[numeric.GreaterEqual[0]]

  object RangeEnd:
    def from(value: Long): Either[String, RangeEnd] = value.refineEither[numeric.GreaterEqual[0]]
    def unsafe(value: Long): RangeEnd               = value.refineUnsafe[numeric.GreaterEqual[0]]
