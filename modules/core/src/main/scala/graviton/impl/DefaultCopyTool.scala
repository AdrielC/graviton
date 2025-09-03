package graviton.impl

import graviton.*
import zio.*

/** Default implementation of [[CopyTool]].
  *
  * Streams bytes from the source [[BinaryStore]] and writes them to the
  * destination. Existing binaries can be skipped or overwritten based on the
  * provided [[Hint]].
  */
final class DefaultCopyTool extends CopyTool:
  private val DefaultChunk: Int = 64 * 1024

  def copy(
      src: BinaryStore,
      dest: BinaryStore,
      id: BinaryId,
      hint: Option[Hint] = None
  ): IO[Throwable, Unit] =
    val transfer = for
      bytes <- src.get(id).someOrFail(GravitonError.NotFound(s"binary $id not found"))
      _ <- bytes.run(dest.put(BinaryAttributes(Map.empty), DefaultChunk)).unit
    yield ()

    for
      exists <- dest.exists(id)
      _ <-
        if exists then
          hint match
            case Some(Hint.Skip)      => ZIO.unit
            case Some(Hint.Overwrite) => transfer
            case _ =>
              ZIO.fail(GravitonError.PolicyViolation(s"binary $id already exists"))
        else transfer
    yield ()

object DefaultCopyTool:
  /** A layer providing a [[CopyTool]] implementation. */
  val layer: ULayer[CopyTool] = ZLayer.succeed(new DefaultCopyTool)
