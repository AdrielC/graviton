package graviton

import zio.*

given scanArrowK: ArrowK[Scan] with
  type Aux[-I, +O, S <: Tuple] = Scan.Aux[I, O, S]

  def id[X]: Aux[X, X, EmptyTuple] = Scan.identity[X]

  def andThen[I, M, O, S1 <: Tuple, S2 <: Tuple](
    left:  Aux[I, M, S1],
    right: Aux[M, O, S2]
  ): Aux[I, O, Tuple.Concat[S1, S2]] = left.andThen(right)

  def zip[I, O1, O2, S1 <: Tuple, S2 <: Tuple](
    left:  Aux[I, O1, S1],
    right: Aux[I, O2, S2]
  ): Aux[I, (O1, O2), Tuple.Concat[S1, S2]] = left.zip(right)

  def product[I1, O1, S1 <: Tuple, I2, O2, S2 <: Tuple](
    left:  Aux[I1, O1, S1],
    right: Aux[I2, O2, S2]
  ): Aux[(I1, I2), (O1, O2), Tuple.Concat[S1, S2]] =
    // Use statefulTuple composition as product for Scan
    val a = left; val b = right
    val sizeA = a.initial.productArity
    Scan.statefulTuple[(I1, I2), (O1, O2), Tuple.Concat[a.State, b.State]](a.initial ++ b.initial) { (st, in) =>
      val s1        = st.take(sizeA).asInstanceOf[a.State]
      val s2        = st.drop(sizeA).asInstanceOf[b.State]
      val (i1, i2)  = in
      val (s1b, o1) = a.step(s1, i1)
      val (s2b, o2) = b.step(s2, i2)
      ((s1b ++ s2b).asInstanceOf[Tuple.Concat[a.State, b.State]], o1.zip(o2))
    } { st =>
      val s1 = st.take(sizeA).asInstanceOf[a.State]
      val s2 = st.drop(sizeA).asInstanceOf[b.State]
      a.done(s1).zip(b.done(s2))
    }

  def first[I, O, C, S <: Tuple](src: Aux[I, O, S]): Aux[(I, C), (O, C), Tuple.Concat[S, Tuple1[Option[C]]]] = src.first[C]

  def second[I, O, C, S <: Tuple](src: Aux[I, O, S]): Aux[(C, I), (C, O), Tuple.Concat[Tuple1[Option[C]], S]] = src.second[C]

  def left[I, O, C, S <: Tuple](src: Aux[I, O, S]): Aux[Either[I, C], Either[O, C], S] = src.left[C]

  def right[I, O, C, S <: Tuple](src: Aux[I, O, S]): Aux[Either[C, I], Either[C, O], S] = src.right[C]

  def plusPlus[I1, O1, S1 <: Tuple, I2, O2, S2 <: Tuple](
    left:  Aux[I1, O1, S1],
    right: Aux[I2, O2, S2]
  ): Aux[Either[I1, I2], Either[O1, O2], Tuple.Concat[S1, S2]] = left +++ right

  def fanIn[I1, O, S1 <: Tuple, I2, S2 <: Tuple](
    left:  Aux[I1, O, S1],
    right: Aux[I2, O, S2]
  ): Aux[Either[I1, I2], O, Tuple.Concat[S1, S2]] = left ||| right

  def statefulTuple[I, O, S <: Tuple](
    initial: S
  )(
    step: (S, I) => (S, Chunk[O])
  )(
    done: S => Chunk[O]
  ): Aux[I, O, S] = Scan.statefulTuple(initial)(step)(done)

