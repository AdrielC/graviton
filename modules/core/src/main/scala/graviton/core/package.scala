package graviton

import zio.stream.*
import zio.schema.*
import scala.collection.immutable.ListMap

package object core:
  type Bytes        = ZStream[Any, Throwable, Byte]
  type StorageError = GravitonError

  export refined.given

  given [K: Schema, V: Schema] => Schema[ListMap[K, V]] =
    Schema.list[((K, V))].transform(ListMap.from, _.toList)

  extension (stream: ZStream[Any, Throwable, Byte])
    def transduceRepeated[A](sink: ZSink[Any, Throwable, Byte, Byte, A]): ZStream[Any, Throwable, A] =
      stream.via(ZPipeline.fromSink(sink))
