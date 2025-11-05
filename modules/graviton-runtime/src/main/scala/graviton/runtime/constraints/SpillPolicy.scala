package graviton.runtime.constraints

import zio.{Scope, ZIO}
import java.nio.file.{Files, Path}

final case class SpillPolicy(root: Path):
  def allocate(prefix: String): ZIO[Scope, Throwable, SpillHandle] =
    ZIO.fromAutoCloseable(ZIO.attempt {
      val dir = Files.createTempDirectory(root, prefix)
      SpillHandle(dir)
    })
