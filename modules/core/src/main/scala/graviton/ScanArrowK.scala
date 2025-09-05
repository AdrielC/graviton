package graviton

import zio.*

given scanArrowK: ArrowK[[i, o, s <: Tuple] =>> Scan.Aux[i, o, s]] with
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

  def first[I, O, C, S <: Tuple](src: Aux[I, O, S]): Aux[(I, C), (O, C), Tuple.Concat[S, Tuple1[Option[C]]]] =
    val a     = src
    val sizeA = a.initial.productArity
    Scan.statefulTuple[(I, C), (O, C), Tuple.Concat[a.State, Tuple1[Option[C]]]](a.initial ++ Tuple1(None)) { (st, in) =>
      val sA        = st.take(sizeA).asInstanceOf[a.State]
      val (i, c)    = in
      val (sAb, oA) = a.step(sA, i)
      ((sAb ++ Tuple1(Some(c))).asInstanceOf[Tuple.Concat[a.State, Tuple1[Option[C]]]], oA.map(o => (o, c)))
    } { st =>
      val sA       = st.take(sizeA).asInstanceOf[a.State]
      val lastCOpt = st.drop(sizeA).asInstanceOf[Tuple1[Option[C]]]._1
      lastCOpt match
        case Some(c) => a.done(sA).map(o => (o, c))
        case None    => Chunk.empty
    }

  def second[I, O, C, S <: Tuple](src: Aux[I, O, S]): Aux[(C, I), (C, O), Tuple.Concat[Tuple1[Option[C]], S]] =
    val a     = src
    val sizeA = 1
    Scan.statefulTuple[(C, I), (C, O), Tuple.Concat[Tuple1[Option[C]], a.State]](Tuple1(None) ++ a.initial) { (st, in) =>
      val sA       = st.drop(sizeA).asInstanceOf[a.State]
      val (c, i)   = in
      val (sAb, o) = a.step(sA, i)
      ((Tuple1(Some(c)) ++ sAb).asInstanceOf[Tuple.Concat[Tuple1[Option[C]], a.State]], o.map(o2 => (c, o2)))
    } { st =>
      val lastCOpt = st.take(sizeA).asInstanceOf[Tuple1[Option[C]]]._1
      val sA       = st.drop(sizeA).asInstanceOf[a.State]
      lastCOpt match
        case Some(c) => a.done(sA).map(o => (c, o))
        case None    => Chunk.empty
    }

  def left[I, O, C, S <: Tuple](src: Aux[I, O, S]): Aux[Either[I, C], Either[O, C], S] =
    val a = src
    Scan.statefulTuple[Either[I, C], Either[O, C], a.State](a.initial) { (s, in) =>
      in match
        case Left(i)  =>
          val (s2, out) = a.step(s, i)
          (s2, out.map(Left(_)))
        case Right(c) => (s, Chunk.single(Right(c)))
    }(s => a.done(s).map(Left(_)))

  def right[I, O, C, S <: Tuple](src: Aux[I, O, S]): Aux[Either[C, I], Either[C, O], S] =
    val a = src
    Scan.statefulTuple[Either[C, I], Either[C, O], a.State](a.initial) { (s, in) =>
      in match
        case Right(i) =>
          val (s2, out) = a.step(s, i)
          (s2, out.map(Right(_)))
        case Left(c)  => (s, Chunk.single(Left(c)))
    }(s => a.done(s).map(Right(_)))

  def plusPlus[I1, O1, S1 <: Tuple, I2, O2, S2 <: Tuple](
    left:  Aux[I1, O1, S1],
    right: Aux[I2, O2, S2]
  ): Aux[Either[I1, I2], Either[O1, O2], Tuple.Concat[S1, S2]] =
    val a     = left
    val b     = right
    val sizeA = a.initial.productArity
    Scan.statefulTuple[Either[I1, I2], Either[O1, O2], Tuple.Concat[a.State, b.State]](a.initial ++ b.initial) { (st, in) =>
      val s1 = st.take(sizeA).asInstanceOf[a.State]
      val s2 = st.drop(sizeA).asInstanceOf[b.State]
      in match
        case Left(i1)  =>
          val (s1b, o1) = a.step(s1, i1)
          ((s1b ++ s2).asInstanceOf[Tuple.Concat[a.State, b.State]], o1.map(Left(_)))
        case Right(i2) =>
          val (s2b, o2) = b.step(s2, i2)
          ((s1 ++ s2b).asInstanceOf[Tuple.Concat[a.State, b.State]], o2.map(Right(_)))
    } { st =>
      val s1 = st.take(sizeA).asInstanceOf[a.State]
      val s2 = st.drop(sizeA).asInstanceOf[b.State]
      a.done(s1).map(Left(_)) ++ b.done(s2).map(Right(_))
    }

  def fanIn[I1, O, S1 <: Tuple, I2, S2 <: Tuple](
    left:  Aux[I1, O, S1],
    right: Aux[I2, O, S2]
  ): Aux[Either[I1, I2], O, Tuple.Concat[S1, S2]] =
    val a     = left
    val b     = right
    val sizeA = a.initial.productArity
    Scan.statefulTuple[Either[I1, I2], O, Tuple.Concat[a.State, b.State]](a.initial ++ b.initial) { (st, in) =>
      val s1 = st.take(sizeA).asInstanceOf[a.State]
      val s2 = st.drop(sizeA).asInstanceOf[b.State]
      in match
        case Left(i1)  =>
          val (s1b, o1) = a.step(s1, i1)
          ((s1b ++ s2).asInstanceOf[Tuple.Concat[a.State, b.State]], o1)
        case Right(i2) =>
          val (s2b, o2) = b.step(s2, i2)
          ((s1 ++ s2b).asInstanceOf[Tuple.Concat[a.State, b.State]], o2)
    } { st =>
      val s1 = st.take(sizeA).asInstanceOf[a.State]
      val s2 = st.drop(sizeA).asInstanceOf[b.State]
      a.done(s1) ++ b.done(s2)
    }

  def statefulTuple[I, O, S <: Tuple](
    initial: S
  )(
    step: (S, I) => (S, Chunk[O])
  )(
    done: S => Chunk[O]
  ): Aux[I, O, S] = Scan.statefulTuple(initial)(step)(done)

