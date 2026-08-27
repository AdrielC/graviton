package graviton.runtime.upload

import graviton.core.types.FileSize
import graviton.runtime.upload.UploadTransportFrame.*
import zio.*
import zio.stream.ZStream

/** Reusable bounded-memory checks for arbitrary upload streams. */
object UploadByteStream:
  sealed trait Error extends IllegalArgumentException

  object Error:
    final case class ByteCountOverflow() extends Error:
      override def getMessage: String = "upload byte count overflow"

    final case class SizeMismatch(expected: FileSize, actual: Long, exceeded: Boolean) extends Error:
      override def getMessage: String =
        if exceeded then s"expected ${expected.value} bytes but received more"
        else s"expected ${expected.value} bytes but received $actual"

    final case class InvalidFrame(reason: String) extends Error:
      override def getMessage: String = s"invalid bounded upload frame: $reason"

  def enforceExpectedSize(
    bytes: ZStream[Any, Throwable, Byte],
    expected: Option[FileSize],
  ): ZStream[Any, Throwable, Byte] =
    expected.fold(bytes) { limit =>
      ZStream.unwrap {
        Ref.make(0L).map { observed =>
          val checked = bytes.mapChunksZIO { chunk =>
            observed.modify { current =>
              if current > Long.MaxValue - chunk.length.toLong then Left(Error.ByteCountOverflow()) -> current
              else
                val actual = current + chunk.length.toLong
                if actual > limit.value then Left(Error.SizeMismatch(limit, actual, exceeded = true)) -> actual
                else Right(chunk)                                                                     -> actual
            }.absolve
          }
          val exact   = ZStream
            .fromZIO(
              observed.get.flatMap { actual =>
                ZIO.fail(Error.SizeMismatch(limit, actual, exceeded = false)).unless(actual == limit.value)
              }
            )
            .drain
          checked ++ exact
        }
      }
    }

  /** Rechunks solely to create the one bounded materializable data-plane value. */
  def observeFrames(
    bytes: ZStream[Any, Throwable, Byte],
    key: UploadSessionKey,
    hotState: UploadHotState,
  ): ZStream[Any, Throwable, Byte] =
    bytes.rechunk(UploadTransportFrame.MaxBytes).mapChunksZIO { chunk =>
      if chunk.isEmpty then ZIO.succeed(chunk)
      else
        ZIO
          .fromEither(UploadTransportFrame.fromChunk(chunk).left.map(Error.InvalidFrame.apply))
          .tap(frame => hotState.observe(key, frame))
          .map(_.bytes)
    }
