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

  def product[I1, O1, S1 <: Tuple, I2, O2, S2 <: Tuple](
    left:  Aux[I1, O1, S1],
    right: Aux[I2, O2, S2]
  ): Aux[(I1, I2), (O1, O2), Tuple.Concat[S1, S2]]

  def first[I, O, C, S <: Tuple](src: Aux[I, O, S]): Aux[(I, C), (O, C), Tuple.Concat[S, Tuple1[Option[C]]]]

  def second[I, O, C, S <: Tuple](src: Aux[I, O, S]): Aux[(C, I), (C, O), Tuple.Concat[Tuple1[Option[C]], S]]

  def left[I, O, C, S <: Tuple](src: Aux[I, O, S]): Aux[Either[I, C], Either[O, C], S]

  def right[I, O, C, S <: Tuple](src: Aux[I, O, S]): Aux[Either[C, I], Either[C, O], S]

  def plusPlus[I1, O1, S1 <: Tuple, I2, O2, S2 <: Tuple](
    left:  Aux[I1, O1, S1],
    right: Aux[I2, O2, S2]
  ): Aux[Either[I1, I2], Either[O1, O2], Tuple.Concat[S1, S2]]

  def fanIn[I1, O, S1 <: Tuple, I2, S2 <: Tuple](
    left:  Aux[I1, O, S1],
    right: Aux[I2, O, S2]
  ): Aux[Either[I1, I2], O, Tuple.Concat[S1, S2]]

  def statefulTuple[I, O, S <: Tuple](
    initial: S
  )(
    step: (S, I) => (S, Chunk[O])
  )(
    done: S => Chunk[O]
  ): Aux[I, O, S]

