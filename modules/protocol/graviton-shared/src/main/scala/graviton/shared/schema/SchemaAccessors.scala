package graviton.shared.schema

import zio.schema.{Schema, StandardType}

object SchemaAccessors {

  final case class Lens[A, B](get: A => B, set: (A, B) => A) {
    def compose[C](that: Lens[B, C]): Lens[A, C] =
      Lens(
        get = get.andThen(that.get),
        set = (a, c) => set(a, that.set(get(a), c)),
      )
  }

  object Lens {
    def identity[A]: Lens[A, A] = Lens(a => a, (_, b) => b)
  }

  final case class StringField[A](path: List[String], label: String, lens: Lens[A, String])

  def stringFields[A](schema: Schema[A]): List[StringField[A]] =
    collectStrings(schema, Lens.identity[A], Nil)

  private def collectStrings[Root, Current](
    schema: Schema[Current],
    lens: Lens[Root, Current],
    path: List[String],
  ): List[StringField[Root]] =
    schema match {
      case record: Schema.Record[Current] @unchecked =>
        record.fields.toList.flatMap { field0 =>
          val field     = field0.asInstanceOf[Schema.Field[Current, Any]]
          val fieldLens = Lens[Current, Any](
            get = field.get,
            set = (rec: Current, value: Any) => field.set(rec, value),
          )
          val nextLens  = lens.compose(fieldLens)
          collectStrings(field.schema.asInstanceOf[Schema[Any]], nextLens.asInstanceOf[Lens[Root, Any]], path :+ field.name.toString)
        }

      case primitive: Schema.Primitive[_] =>
        primitive.standardType match {
          case StandardType.StringType =>
            val label = path.lastOption.getOrElse("value")
            List(StringField(path, label, lens.asInstanceOf[Lens[Root, String]]))
          case _                       => Nil
        }

      case Schema.Lazy(thunk) => collectStrings(thunk(), lens, path)

      case Schema.Optional(_, _) => Nil

      case Schema.Transform(_, _, _, _, _) => Nil

      case _ => Nil
    }
}
