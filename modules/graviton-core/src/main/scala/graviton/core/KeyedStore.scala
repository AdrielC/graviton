package graviton.core

import zio.*
import zio.stream.*

trait KeyedStore:
  def exists(key: FileKey): IO[Throwable, Boolean]
  def delete(key: FileKey): IO[Throwable, Boolean]
  def listKeys(matcher: BinaryKeyMatcher): ZStream[Any, Throwable, FileKey]
