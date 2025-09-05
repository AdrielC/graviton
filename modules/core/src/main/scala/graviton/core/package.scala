package graviton

import zio.stream.*
import zio.schema.*
import scala.collection.immutable.ListMap

package object core:
  type Bytes = ZStream[Any, Throwable, Byte]


  given [K: Schema, V: Schema] => Schema[ListMap[K, V]] =
    Schema.list[((K, V))].transform(ListMap.from, _.toList)