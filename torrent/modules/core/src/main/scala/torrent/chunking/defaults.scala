package torrent
package chunking

import scala.compiletime.ops.long.*

import io.github.iltotore.iron.constraint.all.{ GreaterEqual, LessEqual }
import torrent.schemas.RefinedTypeExt

type ByteSize = ByteSize.T
object ByteSize extends RefinedTypeExt[Long, GreaterEqual[0L] & LessEqual[100L * 1024L * 1024L]]:
  given Conversion[ByteSize, Length]              = ByteSize.applyUnsafe(_).toLength
  extension (size: ByteSize) def toLength: Length = Length.applyUnsafe(size)
  extension (size: ByteSize) def toInt: Int       = size.toInt

  final transparent inline def `1B`: ByteSize    = this.applyUnsafe(1L)
  final transparent inline def `4KB`: ByteSize   = this.applyUnsafe(4L * 1024)
  final transparent inline def `32KB`: ByteSize  = this.applyUnsafe(32L * 1024)
  final transparent inline def `64KB`: ByteSize  = this.applyUnsafe(64L * 1024)
  final transparent inline def `256KB`: ByteSize = this.applyUnsafe(256L * 1024)
  final transparent inline def `1MB`: ByteSize   = this.applyUnsafe(1024L * 1024)
  final transparent inline def `10MB`: ByteSize  = this.applyUnsafe(10L * `1MB`)
  final transparent inline def `50MB`: ByteSize  = this.applyUnsafe(50L * `1MB`)

export ByteSize.*
