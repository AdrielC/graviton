package graviton

import zio.*

trait CopyTool:
  def copy(
      src: BinaryStore,
      dest: BinaryStore,
      id: BinaryId,
      hint: Option[Hint] = None
  ): IO[Throwable, Unit]
