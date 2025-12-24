package graviton.runtime.legacy

import zio.stream.ZStream

trait LegacyFs:
  def open(repo: String, binaryHash: String): ZStream[Any, LegacyRepoError.FsError, Byte]
