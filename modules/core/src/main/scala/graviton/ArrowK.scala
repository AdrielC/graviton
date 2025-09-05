package graviton

import zio.*

trait ArrowK[A[_, _, _]]:
  type Aux[-I, +O, S <: Tuple] = A[I, O, S]

  def id[X]: Aux[X, X, EmptyTuple]

  def andThen[I, M, O, S1 <: Tuple, S2 <: Tuple](
    left:  Aux[I, M, S1],
    right: Aux[M, O, S2]
  ): Aux[I, O, Tuple.Concat[S1, S2]]

  def zip[I, O1, O2, S1 <: Tuple, S2 <: Tuple](
    left:  Aux[I, O1, S1],
    right: Aux[I, O2, S2]
  ): Aux[I, (O1, O2), Tuple.Concat[S1, S2]]

  def statefulTuple[I, O, S <: Tuple](
    initial: S
  )(
    step: (S, I) => (S, Chunk[O])
  )(
    done: S => Chunk[O]
  ): Aux[I, O, S]

