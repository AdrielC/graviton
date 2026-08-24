package graviton.scan

import Scan.{Choice as ScanChoice, *}

/** Composition operators for Scan.
 *
 *  Provided as extension methods so that they can be invoked naturally
 *  (`scan1 >>> scan2`) without interfering with the GADT inheritance
 *  structure of Scan itself.
 *
 *  The operators are defined in terms of the GADT nodes: they build
 *  [[AndThen]], [[Fanout]], [[Product]], [[Choice]] nodes. They also
 *  perform algebraic simplifications inline:
 *
 *  - `id >>> x == x` and `x >>> id == x`
 *  - `Arr(f) >>> Arr(g) == Arr(g ∘ f)` (function fusion)
 *  - `FusedArr(fs) >>> Arr(g) == FusedArr(fs :+ g)` (chain extension)
 *  - `FusedArr(fs) >>> FusedArr(gs) == FusedArr(fs ++ gs)` (chain concat)
 *
 *  The fusion threshold prevents unbounded function chaining. Beyond
 *  [[Compose.FusionThreshold]] composed functions, further composition
 *  creates a new AndThen node rather than extending the chain, so that
 *  the runtime doesn't have to iterate through a multi-thousand-element
 *  array on every single step. This is the Cats `AndThen` trick.
 */
object Compose:

  /** Maximum length of a single fused function chain. Chosen somewhat
   *  arbitrarily: long enough that typical pipelines (10-20 stages) fuse
   *  completely, short enough that the array-iteration cost of the fused
   *  form stays in L1 cache. Benchmark and tune if needed.
   */
  val FusionThreshold: Int = 128

  extension [I, O, SL, EL](self: Aux[I, O, SL, EL])

    /** Sequential composition. */
    infix def >>>[P, SR, ER](that: Aux[O, P, SR, ER]): Aux[I, P, ComposeState[SL, SR], EL & ER] =
      (self, that) match
        // Identity elimination.
        case (Id, r)   => r.asInstanceOf[Aux[I, P, ComposeState[SL, SR], EL & ER]]
        case (l, Id)   => l.asInstanceOf[Aux[I, P, ComposeState[SL, SR], EL & ER]]

        // Function fusion: two Arrs become a FusedArr with the functions
        // chained. We represent as IArray[Any => Any] for direct calling
        // without allocation on the hot path.
        case (Arr(f), Arr(g)) =>
          FusedArr[I, P](IArray[Any => Any](
            f.asInstanceOf[Any => Any],
            g.asInstanceOf[Any => Any],
          )).asInstanceOf[Aux[I, P, ComposeState[SL, SR], EL & ER]]

        // Extend a fused chain with one more function, up to threshold.
        case (FusedArr(fs), Arr(g)) if fs.length < FusionThreshold =>
          FusedArr[I, P](fs :+ g.asInstanceOf[Any => Any])
            .asInstanceOf[Aux[I, P, ComposeState[SL, SR], EL & ER]]

        case (Arr(f), FusedArr(gs)) if gs.length < FusionThreshold =>
          FusedArr[I, P](f.asInstanceOf[Any => Any] +: gs)
            .asInstanceOf[Aux[I, P, ComposeState[SL, SR], EL & ER]]

        // Concatenate two fused chains if combined length is within threshold.
        case (FusedArr(fs), FusedArr(gs)) if fs.length + gs.length <= FusionThreshold =>
          FusedArr[I, P](fs ++ gs).asInstanceOf[Aux[I, P, ComposeState[SL, SR], EL & ER]]

        // Fall-through: ordinary AndThen.
        case (l, r) =>
          AndThen[I, O, P, SL, SR, EL, ER](
            l.asInstanceOf[Aux[I, O, SL, EL]],
            r.asInstanceOf[Aux[O, P, SR, ER]],
          ).asInstanceOf[Aux[I, P, ComposeState[SL, SR], EL & ER]]

    /** Fanout: broadcast input to both, pair outputs. */
    infix def &&&[P, SR, ER](that: Aux[I, P, SR, ER]): Aux[I, (O, P), FanoutState[SL, SR, O, P], EL & ER] =
      Fanout[I, O, P, SL, SR, EL, ER](self, that)
        .asInstanceOf[Aux[I, (O, P), FanoutState[SL, SR, O, P], EL & ER]]

    /** Product: parallel on tuples. */
    infix def ***[I2, O2, SR, ER](that: Aux[I2, O2, SR, ER]): Aux[(I, I2), (O, O2), ComposeState[SL, SR], EL & ER] =
      Product[I, I2, O, O2, SL, SR, EL, ER](self, that)
        .asInstanceOf[Aux[(I, I2), (O, O2), ComposeState[SL, SR], EL & ER]]

    /** Choice: route Either inputs to the matching side. */
    infix def |||[I2, O2 >: O, SR, ER](that: Aux[I2, O2, SR, ER]): Aux[Either[I, I2], O2, ComposeState[SL, SR], EL & ER] =
      ScanChoice[I, I2, O, O2, SL, SR, EL, ER](self, that)
        .asInstanceOf[Aux[Either[I, I2], O2, ComposeState[SL, SR], EL & ER]]

    /** Profunctor map on outputs. Fuses into the underlying Arr/FusedArr if the
     *  scan is already pure, otherwise wraps with a trailing Arr.
     */
    def map[P](f: O => P): Aux[I, P, SL, EL] =
      self match
        case Arr(g)       => Arr(g.asInstanceOf[I => O].andThen(f)).asInstanceOf[Aux[I, P, SL, EL]]
        case FusedArr(fs) if fs.length < FusionThreshold =>
          FusedArr[I, P](fs :+ f.asInstanceOf[Any => Any]).asInstanceOf[Aux[I, P, SL, EL]]
        case _            => (self >>> Arr(f.asInstanceOf[O => P])).asInstanceOf[Aux[I, P, SL, EL]]

    /** Profunctor map on inputs. */
    def contramap[J](f: J => I): Aux[J, O, SL, EL] =
      (Arr(f.asInstanceOf[J => I]) >>> self).asInstanceOf[Aux[J, O, SL, EL]]

    /** dimap: both sides at once. */
    def dimap[J, P](pre: J => I)(post: O => P): Aux[J, P, SL, EL] =
      self.contramap(pre).map(post)

end Compose

export Compose.*
