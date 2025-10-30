package graviton.runtime.constraints

import java.nio.file.Path

final case class SpillPath(base: Path, prefix: String)
