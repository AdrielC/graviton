package graviton

import zio.*
import zio.stream.*

type Bytes = ZStream[Any, Throwable, Byte]
