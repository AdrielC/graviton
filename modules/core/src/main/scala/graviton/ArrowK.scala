package graviton

import zio.*

trait ArrowK[A[_, _, _]]:
  def id[I]: A[I, I, EmptyTuple]

  def andThen[I, M, O, S1 <: Tuple, S2 <: Tuple](
    left: A[I, M, S1],
    right: A[M, O, S2],
  ): A[I, O, Tuple.Concat[S1, S2]]

  def zip[I, O1, O2, S1 <: Tuple, S2 <: Tuple](
    left: A[I, O1, S1],
    right: A[I, O2, S2],
  ): A[I, (O1, O2), Tuple.Concat[S1, S2]]

  def product[I1, O1, S1 <: Tuple, I2, O2, S2 <: Tuple](
    left: A[I1, O1, S1],
    right: A[I2, O2, S2],
  ): A[(I1, I2), (O1, O2), Tuple.Concat[S1, S2]]

  def first[I, O, C, S <: Tuple](src: A[I, O, S]): A[(I, C), (O, C), Tuple.Concat[S, Tuple1[Option[C]]]]

  def second[I, O, C, S <: Tuple](src: A[I, O, S]): A[(C, I), (C, O), Tuple.Concat[Tuple1[Option[C]], S]]

  def left[I, O, C, S <: Tuple](src: A[I, O, S]): A[Either[I, C], Either[O, C], S]

  def right[I, O, C, S <: Tuple](src: A[I, O, S]): A[Either[C, I], Either[C, O], S]

  def plusPlus[I1, O1, S1 <: Tuple, I2, O2, S2 <: Tuple](
    left: A[I1, O1, S1],
    right: A[I2, O2, S2],
  ): A[Either[I1, I2], Either[O1, O2], Tuple.Concat[S1, S2]]

  def fanIn[I1, O, S1 <: Tuple, I2, S2 <: Tuple](
    left: A[I1, O, S1],
    right: A[I2, O, S2],
  ): A[Either[I1, I2], O, Tuple.Concat[S1, S2]]

  def statefulTuple[I, O, S <: Tuple](
    initial: S
  )(
    step: (S, I) => (S, Chunk[O])
  )(
    done: S => Chunk[O]
  ): A[I, O, S]
