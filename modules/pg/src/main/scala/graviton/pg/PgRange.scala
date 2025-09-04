package graviton.pg

/** Simple representation of a PostgreSQL range type. */
final case class PgRange[T](lower: Option[T], upper: Option[T])
