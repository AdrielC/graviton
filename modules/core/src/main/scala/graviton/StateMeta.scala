package graviton

import zio.Chunk

/** Minimal state metadata for tuple states (labels only for now). */
final case class StateMeta[S <: Tuple](labels: Chunk[String], size: Int)

object StateMeta:
  def unit: StateMeta[EmptyTuple] = StateMeta(Chunk.empty, 0)

  def single[A](label: String): StateMeta[A *: EmptyTuple] =
    StateMeta(Chunk(label), 1)

  def product[S1 <: Tuple, S2 <: Tuple](m1: StateMeta[S1], m2: StateMeta[S2]): StateMeta[Tuple.Concat[S1, S2]] =
    StateMeta(m1.labels ++ m2.labels, m1.size + m2.size)

  def rename[S <: Tuple](m: StateMeta[S])(f: Chunk[String] => Chunk[String]): StateMeta[S] =
    m.copy(labels = f(m.labels))
