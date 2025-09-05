package graviton

import zio.*

/**
 * A free, compositional description of a Scan. It can be built up
 * using pure combinators and later compiled into a concrete `Scan`
 * whose `State` type is derived at compile time.
 */
sealed trait FreeScan[-I, +O]:
  type State <: Tuple

  /** Compile this free description into a concrete `Scan`. */
  def compile: Scan.Aux[I, O, State]

object FreeScan:
  type Aux[-I, +O, S <: Tuple] = FreeScan[I, O] { type State = S }

  // ---------------- Constructors ----------------

  final case class Identity[I]() extends FreeScan[I, I]:
    type State = EmptyTuple
    def compile: Scan.Aux[I, I, EmptyTuple] = Scan.identity[I]

  final case class Stateless1[I, O](f: I => O) extends FreeScan[I, O]:
    type State = EmptyTuple
    def compile: Scan.Aux[I, O, EmptyTuple] = Scan.stateless1(f)

  final case class Stateless[I, O](f: I => Chunk[O]) extends FreeScan[I, O]:
    type State = EmptyTuple
    def compile: Scan.Aux[I, O, EmptyTuple] = Scan.stateless(f)

  final case class Stateful[I, O, S <: Tuple](
    init: S,
    stepFn: (S, I) => (S, Chunk[O]),
    doneFn: S => Chunk[O],
  ) extends FreeScan[I, O]:
    type State = S
    def compile: Scan.Aux[I, O, S] = Scan.statefulTuple(init)(stepFn)(doneFn)

  // ---------------- Combinators ----------------

  extension [I, O, S <: Tuple](self: FreeScan.Aux[I, O, S])
    def map[O2](f: O => O2): FreeScan.Aux[I, O2, S] =
      Mapped(self, f)

    def contramap[I2](g: I2 => I): FreeScan.Aux[I2, O, S] =
      ContraMapped(self, g)

    def dimap[I2, O2](g: I2 => I)(f: O => O2): FreeScan.Aux[I2, O2, S] =
      Dimapped(self, g, f)

    def andThen[O2, S2 <: Tuple](that: FreeScan.Aux[O, O2, S2]): FreeScan.Aux[I, O2, Tuple.Concat[S, S2]] =
      Composed(self, that)

    def zip[O2, S2 <: Tuple](that: FreeScan.Aux[I, O2, S2]): FreeScan.Aux[I, (O, O2), Tuple.Concat[S, S2]] =
      Zipped(self, that)

    /** Alias for `compile`. */
    def toScan: Scan.Aux[I, O, S] = self.compile

  final case class Mapped[I, O, S <: Tuple, O2](
    src: FreeScan.Aux[I, O, S],
    f: O => O2,
  ) extends FreeScan[I, O2]:
    type State = S
    def compile: Scan.Aux[I, O2, S] = src.compile.map(f)

  final case class ContraMapped[I, O, S <: Tuple, I2](
    src: FreeScan.Aux[I, O, S],
    g: I2 => I,
  ) extends FreeScan[I2, O]:
    type State = S
    def compile: Scan.Aux[I2, O, S] = src.compile.contramap(g)

  final case class Dimapped[I, O, S <: Tuple, I2, O2](
    src: FreeScan.Aux[I, O, S],
    g: I2 => I,
    f: O => O2,
  ) extends FreeScan[I2, O2]:
    type State = S
    def compile: Scan.Aux[I2, O2, S] = src.compile.dimap(g)(f)

  final case class Composed[I, O, S <: Tuple, O2, S2 <: Tuple](
    left: FreeScan.Aux[I, O, S],
    right: FreeScan.Aux[O, O2, S2],
  ) extends FreeScan[I, O2]:
    type State = Tuple.Concat[S, S2]
    def compile: Scan.Aux[I, O2, Tuple.Concat[S, S2]] = left.compile.andThen(right.compile)

  final case class Zipped[I, O, S <: Tuple, O2, S2 <: Tuple](
    left: FreeScan.Aux[I, O, S],
    right: FreeScan.Aux[I, O2, S2],
  ) extends FreeScan[I, (O, O2)]:
    type State = Tuple.Concat[S, S2]
    def compile: Scan.Aux[I, (O, O2), Tuple.Concat[S, S2]] = left.compile.zip(right.compile)

  // ---------------- Helpers ----------------

  def identity[I]: Aux[I, I, EmptyTuple] = Identity[I]()

  def lift[I, O](f: I => O): Aux[I, O, EmptyTuple] = Stateless1(f)

  def stateless1[I, O](f: I => O): Aux[I, O, EmptyTuple] = Stateless1(f)

  def stateless[I, O](f: I => Chunk[O]): Aux[I, O, EmptyTuple] = Stateless(f)

  def stateful[I, O, S](init: S)(
    stepFn: (S, I) => (S, Chunk[O])
  )(
    doneFn: S => Chunk[O]
  ): Aux[I, O, S *: EmptyTuple] =
    Stateful[I, O, S *: EmptyTuple](
      init = init *: EmptyTuple,
      stepFn = (st: S *: EmptyTuple, i: I) =>
        val (s, outs) = stepFn(st.head, i)
        (s *: EmptyTuple, outs),
      doneFn = (st: S *: EmptyTuple) => doneFn(st.head),
    )

  def statefulTuple[I, O, S <: Tuple](init: S)(
    stepFn: (S, I) => (S, Chunk[O])
  )(
    doneFn: S => Chunk[O]
  ): Aux[I, O, S] =
    Stateful(init, stepFn, doneFn)

