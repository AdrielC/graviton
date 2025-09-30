package graviton.core

import io.github.iltotore.iron.{zio => _, *}
import io.github.iltotore.iron.RuntimeConstraint
import zio.schema.*
import zio.schema.annotation.description

object refined:
  given [A, C](using schema: Schema[A], constraint: RuntimeConstraint[A, C]): Schema[A :| C] =
    schema
      .annotate(description(constraint.message))
      .transformOrFail(
        a =>
          a
            .refineEither[C]
            .left
            .map(err => s"${constraint.message}: $err"),
        refined => Right(refined.asInstanceOf[A]),
      )
