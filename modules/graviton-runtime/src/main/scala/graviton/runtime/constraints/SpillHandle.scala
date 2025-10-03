package graviton.runtime.constraints

import java.nio.file.Path

final case class SpillHandle(path: Path) extends AutoCloseable:
  override def close(): Unit = ()
