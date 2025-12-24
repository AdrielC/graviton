package graviton.runtime.legacy

import zio.stream.ZStream

trait CedarFs:
  def open(repo: String, binaryHash: String): ZStream[Any, CedarLegacyError.FsError, Byte]
