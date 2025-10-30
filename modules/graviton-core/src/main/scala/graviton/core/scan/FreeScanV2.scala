package graviton.core.scan

import zio.Chunk

/**
 * Free Scan - simple, testable, composable.
 *
 * Simplified from the complex designs - just works.
 */

/**
 * A scan with hidden state.
 */
trait Scan[In, Out]:
  type S
  def init: S
  def step(state: S, input: In): (S, Chunk[Out])
  def flush(state: S): Chunk[Out]

object Scan:

  type Aux[In, Out, S0] = Scan[In, Out] { type S = S0 }

  /** Identity */
  def id[A]: Aux[A, A, Unit] = new Scan[A, A]:
    type S = Unit
    def init                        = ()
    def step(state: Unit, input: A) = ((), Chunk.single(input))
    def flush(state: Unit)          = Chunk.empty

  /** Pure function */
  def arr[In, Out](f: In => Out): Aux[In, Out, Unit] = new Scan[In, Out]:
    type S = Unit
    def init                         = ()
    def step(state: Unit, input: In) = ((), Chunk.single(f(input)))
    def flush(state: Unit)           = Chunk.empty

  /** Stateful fold */
  def fold[In, S0](z: S0)(f: (S0, In) => S0): Aux[In, S0, S0] = new Scan[In, S0]:
    type S = S0
    def init                      = z
    def step(state: S, input: In) =
      val s2 = f(state, input)
      (s2, Chunk.single(s2))
    def flush(state: S)           = Chunk.empty

  /** Sequential composition */
  def andThen[In, Mid, Out, SA, SB](
    left: Aux[In, Mid, SA],
    right: Aux[Mid, Out, SB],
  ): Aux[In, Out, (SA, SB)] = new Scan[In, Out]:
    type S = (SA, SB)
    def init                             = (left.init, right.init)
    def step(state: (SA, SB), input: In) =
      val (sA, sB)        = state
      val (sA2, midChunk) = left.step(sA, input)
      var sBCurr          = sB
      val outputs         = Chunk.newBuilder[Out]
      midChunk.foreach { mid =>
        val (sB2, outChunk) = right.step(sBCurr, mid)
        sBCurr = sB2
        outputs ++= outChunk
      }
      ((sA2, sBCurr), outputs.result())
    def flush(state: (SA, SB))           =
      val (sA, sB)  = state
      val leftFlush = left.flush(sA)
      var sBCurr    = sB
      val outputs   = Chunk.newBuilder[Out]
      leftFlush.foreach { mid =>
        val (sB2, outChunk) = right.step(sBCurr, mid)
        sBCurr = sB2
        outputs ++= outChunk
      }
      outputs ++= right.flush(sBCurr)
      outputs.result()

  /** Fanout */
  def fanout[In, Out1, Out2, S1, S2](
    left: Aux[In, Out1, S1],
    right: Aux[In, Out2, S2],
  ): Aux[In, (Out1, Out2), (S1, S2)] = new Scan[In, (Out1, Out2)]:
    type S = (S1, S2)
    def init                             = (left.init, right.init)
    def step(state: (S1, S2), input: In) =
      val (s1, s2)      = state
      val (s1New, out1) = left.step(s1, input)
      val (s2New, out2) = right.step(s2, input)
      val combined      = out1.zip(out2)
      ((s1New, s2New), combined)
    def flush(state: (S1, S2))           =
      val (s1, s2) = state
      val f1       = left.flush(s1)
      val f2       = right.flush(s2)
      f1.zip(f2)

/** Extensions */
extension [In, Mid, SA](left: Scan.Aux[In, Mid, SA])
  infix def >>>[Out, SB](right: Scan.Aux[Mid, Out, SB]): Scan.Aux[In, Out, (SA, SB)] =
    Scan.andThen(left, right)

  infix def &&&[Out2, SB](right: Scan.Aux[In, Out2, SB]): Scan.Aux[In, (Mid, Out2), (SA, SB)] =
    Scan.fanout(left, right)

  def map[Out](f: Mid => Out): Scan.Aux[In, Out, SA] = new Scan[In, Out]:
    type S = SA
    def init                      = left.init
    def step(state: S, input: In) =
      val (s2, outs) = left.step(state, input)
      (s2, outs.map(f))
    def flush(state: S)           = left.flush(state).map(f)

  def contramap[In0](f: In0 => In): Scan.Aux[In0, Mid, SA] = new Scan[In0, Mid]:
    type S = SA
    def init                       = left.init
    def step(state: S, input: In0) = left.step(state, f(input))
    def flush(state: S)            = left.flush(state)
