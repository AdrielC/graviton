package graviton.core

import io.github.iltotore.iron.{zio as _, *}
// import io.github.iltotore.iron.constraint.all.*
import zio.schema.Schema
import zio.prelude.NonEmptyMap
import zio.*

transparent trait SubtypeExt[A, C] extends RefinedSubtype[A, C]:
  self =>

  given (schema: Schema[A]) => Schema[T] =
    schema
      .annotate(rtc)
      .transformOrFail(either(_), a => Right(a.value))

end SubtypeExt


given [K: Schema, V: Schema] => Schema[NonEmptyMap[K, V]] =
  Schema.list[((K, V))].transformOrFail(
    NonEmptyMap.fromIterableOption(_).toRight("NonEmptyMap cannot be empty"), 
    nem => Right(nem.toList)
  )

type Name = String & Singleton

