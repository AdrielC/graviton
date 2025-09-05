package graviton

import zio.Chunk
import zio.schema.Schema

/** Minimal FreeInvariantMonoidal-like state metadata for tuple states. */
final case class StateMeta[S <: Tuple](labels: Chunk[String], schema: Schema[S], size: Int)

object StateMeta:
  def unit: StateMeta[EmptyTuple] =
    val emptySchema: Schema[EmptyTuple] = Schema[Unit].transform(_ => EmptyTuple, _ => ())
    StateMeta(Chunk.empty, emptySchema, 0)

  def single[A](label: String)(using sa: Schema[A]): StateMeta[A *: EmptyTuple] =
    val sch: Schema[A *: EmptyTuple] = sa.transform[A *: EmptyTuple](a => a *: EmptyTuple, { case h *: _ => h })
    StateMeta(Chunk(label), sch, 1)

  def product[S1 <: Tuple, S2 <: Tuple](m1: StateMeta[S1], m2: StateMeta[S2]): StateMeta[Tuple.Concat[S1, S2]] =
    // Compose schemas using tuple2 folding; this is a minimal placeholder composition
    val sch: Schema[Tuple.Concat[S1, S2]] =
      Schema.tuple2[S1, S2].asInstanceOf[Schema[Tuple.Concat[S1, S2]]]
    StateMeta(m1.labels ++ m2.labels, sch, m1.size + m2.size)

  def rename[S <: Tuple](m: StateMeta[S])(f: Chunk[String] => Chunk[String]): StateMeta[S] =
    m.copy(labels = f(m.labels))

