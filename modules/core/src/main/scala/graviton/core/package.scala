package graviton

import zio.*
import zio.stream.*

package object core:
  type Bytes = ZStream[Any, Throwable, Byte]
