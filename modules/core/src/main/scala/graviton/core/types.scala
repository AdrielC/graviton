package graviton.core

import io.github.iltotore.iron.{zio as _, *}
// import io.github.iltotore.iron.constraint.all.*
import zio.schema.Schema
import zio.prelude.NonEmptySortedMap
import zio.*

transparent trait SubtypeExt[A, C] extends RefinedSubtype[A, C]:
  self =>

  given (schema: Schema[A]) => Schema[T] =
    schema
      .annotate(rtc)
      .transformOrFail(either(_), a => Right(a.value))

end SubtypeExt

given [K: Schema, A: Schema] => Schema[NonEmptySortedMap[K, A]] =
  Schema.nonEmptyChunk[((K, A))]
  .transform(
    (o: NonEmptyChunk[((K, A))]) => NonEmptySortedMap.fromNonEmptyChunk(o)(using Schema[K].ordering),
    s => NonEmptyChunk.fromIterableOption(s.toList).get
  )

given [K: Ordering, A] => Conversion[NonEmptyChunk[((K, A))], NonEmptySortedMap[K, A]] =
  NonEmptySortedMap.fromNonEmptyChunk(_)

given [K, A] => Conversion[NonEmptySortedMap[K, A], NonEmptyChunk[((K, A))]] =
  s => NonEmptyChunk.fromIterableOption(s.toList).get


extension [K, A](s: NonEmptyChunk[((K, A))])
  def toNonEmptySortedMap(using Ordering[K]): NonEmptySortedMap[K, A] =
    NonEmptySortedMap.fromNonEmptyChunk(s)
  
  def mapZIOSortedMap[R, E, B](f: (K, A) => ZIO[R, E, B])(using Ordering[K]): ZIO[R, E, NonEmptySortedMap[K, B]] =
    s
    .toChunk
    .mapZIO(c => f(c._1, c._2).map(b => (c._1, b)))
    .map(NonEmptyChunk.fromChunk(_).get.toNonEmptySortedMap)


extension [K, A](s: NonEmptySortedMap[K, A])

  def toNonEmptyChunk: NonEmptyChunk[((K, A))] =
    NonEmptyChunk.fromIterableOption(s.toList).get

  def mapZIO[R, E, B](f: (K, A) => ZIO[R, E, B])(using Ordering[K]): ZIO[R, E, NonEmptySortedMap[K, B]] =
    s.toNonEmptyChunk.mapZIOSortedMap(f)


type Name = String