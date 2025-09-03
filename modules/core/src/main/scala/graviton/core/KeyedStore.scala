package graviton.core

import zio.*
import zio.stream.*

trait KeyedStore:
  def exists(key: BinaryKey): IO[Throwable, Boolean]
  def delete(key: BinaryKey): IO[Throwable, Boolean]
  def listKeys(matcher: BinaryKeyMatcher): ZStream[Any, Throwable, BinaryKey]
