package graviton.core.scan

import zio.Chunk

/**
 * Arrow-polymorphic Scan kernel.
 *
 * A scan is a single-pass streaming transformation with hidden state:
 * - Input type: I
 * - Output type: O  
 * - Arrow type: Op[_, _] (Pure, KleisliZIO, RWSTZIO)
 * - Hidden state: S (type member)
 * - State spec: StateF[S] (free IM description)
 * - Init: F[S] (parametric IM constructor)
 *
 * Core operations:
 * - step: Op[(S, I), (S, Chunk[O])] - Process one input
 * - flush: Op[S, Chunk[O]] - Emit trailing outputs
 *
 * The scan stays pure until you compose it with an effectful scan,
 * then the whole pipeline promotes to the higher capability arrow.
 *
 * Laws:
 * - Determinism (pure): same inputs => same outputs + state
 * - Flush: fullOutput = foldLeft(inputs)(step) ++ flush(finalState)
 * - Single-pass: no buffering except bounded Chunk assembly
 *
 * Example:
 * {{{
 *   val countBytes: Scan[Function1, Byte, Long] = ???
 *   val hashBlake3: Scan[Function1, Byte, Digest] = ???
 *   
 *   // Compose with &&&, stays pure
 *   val fused = countBytes &&& hashBlake3
 *   
 *   // Add effectful step, auto-promotes to Kleisli
 *   val withEffect: Scan[KleisliZIO[R, E, *, *], Byte, (Long, Digest)] = ???
 * }}}
 */
trait Scan[Op[_, _], -I, +O]:
  
  /** Hidden state type */
  type S
  
  /** Free IM state description */
  def stateSpec: StateF[S]
  
  /** Parametric IM init constructor */
  def initF[F[_]](using InitF[F]): F[S]
  
  /** Convert init to an operation in the arrow */
  def toInitOp(using ak: ArrowKind[Op], initToOp: InitToOp[EvalInit, Op]): Op[Unit, S] =
    initToOp(initF[EvalInit])
  
  /** Step function: (state, input) => (newState, outputs) */
  def step: Op[(S, I), (S, Chunk[O])]
  
  /** Finalizer: finalState => trailing outputs */
  def flush: Op[S, Chunk[O]]

object Scan:
  
  /**
   * Aux pattern to expose hidden state type.
   */
  type Aux[Op[_, _], I, O, S0] = Scan[Op, I, O] { type S = S0 }
  
  /**
   * Construct a scan from components.
   */
  def apply[Op[_, _], I, O, S0](
    stateSpec0: StateF[S0],
    initF0: [F[_]] => InitF[F] ?=> F[S0],
    step0: Op[(S0, I), (S0, Chunk[O])],
    flush0: Op[S0, Chunk[O]]
  ): Aux[Op, I, O, S0] =
    new Scan[Op, I, O]:
      type S = S0
      def stateSpec = stateSpec0
      def initF[F[_]](using InitF[F]): F[S] = initF0[F]
      def step = step0
      def flush = flush0
  
  /**
   * Identity scan (pure).
   */
  def identity[I]: Aux[Function1, I, I, Unit] =
    Scan[Function1, I, I, Unit](
      stateSpec0 = StateF.unit,
      initF0 = [F[_]] => (initF: InitF[F]) ?=> initF.unit,
      step0 = { case (s, i) => (s, Chunk.single(i)) },
      flush0 = _ => Chunk.empty
    )
  
  /**
   * Pure function scan (arr).
   */
  def arr[I, O](f: I => O): Aux[Function1, I, O, Unit] =
    Scan[Function1, I, O, Unit](
      stateSpec0 = StateF.unit,
      initF0 = [F[_]] => (initF: InitF[F]) ?=> initF.unit,
      step0 = { case (s, i) => (s, Chunk.single(f(i))) },
      flush0 = _ => Chunk.empty
    )
  
  /**
   * Stateless scan that emits nothing (const Empty).
   */
  def empty[Op[_, _], I, O](using ak: ArrowKind[Op]): Aux[Op, I, O, Unit] =
    Scan[Op, I, O, Unit](
      stateSpec0 = StateF.unit,
      initF0 = [F[_]] => (initF: InitF[F]) ?=> initF.unit,
      step0 = ak.arr { case (s, _) => (s, Chunk.empty) },
      flush0 = ak.arr(_ => Chunk.empty)
    )
  
  /**
   * Fold-left scan (stateful accumulation).
   */
  def foldLeft[Op[_, _], I, S0](
    stateSpec0: StateF[S0],
    init0: [F[_]] => InitF[F] ?=> F[S0],
    f: (S0, I) => S0
  )(using ak: ArrowKind[Op]): Aux[Op, I, S0, S0] =
    Scan[Op, I, S0, S0](
      stateSpec0 = stateSpec0,
      initF0 = init0,
      step0 = ak.arr { case (s, i) => 
        val s2 = f(s, i)
        (s2, Chunk.single(s2))
      },
      flush0 = ak.arr(s => Chunk.empty)
    )
  
  /**
   * Scan with state but no outputs until flush.
   */
  def accum[Op[_, _], I, S0, O](
    stateSpec0: StateF[S0],
    init0: [F[_]] => InitF[F] ?=> F[S0],
    step0: (S0, I) => S0,
    finalize: S0 => O
  )(using ak: ArrowKind[Op]): Aux[Op, I, O, S0] =
    Scan[Op, I, O, S0](
      stateSpec0 = stateSpec0,
      initF0 = init0,
      step0 = ak.arr { case (s, i) => (step0(s, i), Chunk.empty) },
      flush0 = ak.arr(s => Chunk.single(finalize(s)))
    )

/**
 * Extension methods for Scan composition.
 */
extension [Op[_, _], I, O, SA](scan: Scan.Aux[Op, I, O, SA])
  
  /**
   * Map output (functor).
   */
  def mapOut[O2](f: O => O2)(using ak: ArrowKind[Op]): Scan.Aux[Op, I, O2, SA] =
    Scan[Op, I, O2, SA](
      stateSpec0 = scan.stateSpec,
      initF0 = [F[_]] => (initF: InitF[F]) ?=> scan.initF[F],
      step0 = ak.compose(
        scan.step,
        ak.arr { case (s, chunk) => (s, chunk.map(f)) }
      ),
      flush0 = ak.compose(scan.flush, ak.arr(_.map(f)))
    )
  
  /**
   * Contramap input (contravariant).
   */
  def contramapIn[I2](f: I2 => I)(using ak: ArrowKind[Op]): Scan.Aux[Op, I2, O, SA] =
    Scan[Op, I2, O, SA](
      stateSpec0 = scan.stateSpec,
      initF0 = [F[_]] => (initF: InitF[F]) ?=> scan.initF[F],
      step0 = ak.compose(
        ak.arr { case (s, i2) => (s, f(i2)) },
        scan.step
      ),
      flush0 = scan.flush
    )
  
  /**
   * Dimap: contramap input + map output (profunctor).
   */
  def dimap[I2, O2](f: I2 => I)(g: O => O2)(using ak: ArrowKind[Op]): Scan.Aux[Op, I2, O2, SA] =
    scan.contramapIn(f).mapOut(g)

/**
 * Composition combinators (>>>, &&&, |||).
 * 
 * These use Joiner to automatically promote capabilities.
 */
object ScanComposition:
  
  /**
   * Sequential composition (>>>).
   * 
   * Output of first scan becomes input to second.
   * States are composed via product.
   */
  def andThen[Op1[_, _], Op2[_, _], I, M, O, SA, SB](
    left: Scan.Aux[Op1, I, M, SA],
    right: Scan.Aux[Op2, M, O, SB]
  )(using j: Joiner[Op1, Op2]): Scan.Aux[j.Out, I, O, (SA, SB)] =
    
    val stateSpec0: StateF[(SA, SB)] = StateF.product(left.stateSpec, right.stateSpec)
    
    val initF0: [F[_]] => InitF[F] ?=> F[(SA, SB)] =
      [F[_]] => (initF: InitF[F]) ?=> initF.product(left.initF[F], right.initF[F])
    
    // Step: run left, feed outputs to right
    val step0: j.Out[((SA, SB), I), ((SA, SB), Chunk[O])] =
      j.AK.arr { case ((sA, sB), i) =>
        // Lift left.step to Out
        val leftLifted = j.lift1(left.step)
        // Run left.step (would need proper arrow eval here)
        // For now, placeholder
        ???
      }
    
    val flush0: j.Out[(SA, SB), Chunk[O]] =
      ???
    
    Scan[j.Out, I, O, (SA, SB)](stateSpec0, initF0, step0, flush0)
  
  /**
   * Fanout (&&&).
   * 
   * Run both scans on the same input, produce tuple of outputs.
   * States are composed via product.
   */
  def fanout[Op1[_, _], Op2[_, _], I, O1, O2, SA, SB](
    left: Scan.Aux[Op1, I, O1, SA],
    right: Scan.Aux[Op2, I, O2, SB]
  )(using j: Joiner[Op1, Op2]): Scan.Aux[j.Out, I, (O1, O2), (SA, SB)] =
    
    val stateSpec0: StateF[(SA, SB)] = StateF.product(left.stateSpec, right.stateSpec)
    
    val initF0: [F[_]] => InitF[F] ?=> F[(SA, SB)] =
      [F[_]] => (initF: InitF[F]) ?=> initF.product(left.initF[F], right.initF[F])
    
    val step0: j.Out[((SA, SB), I), ((SA, SB), Chunk[(O1, O2)])] =
      ???
    
    val flush0: j.Out[(SA, SB), Chunk[(O1, O2)]] =
      ???
    
    Scan[j.Out, I, (O1, O2), (SA, SB)](stateSpec0, initF0, step0, flush0)

/**
 * Infix operators for scan composition.
 */
extension [Op1[_, _], I, M, SA](left: Scan.Aux[Op1, I, M, SA])
  
  /**
   * Sequential composition (>>>).
   */
  infix def >>>[Op2[_, _], O, SB](right: Scan.Aux[Op2, M, O, SB])(
    using j: Joiner[Op1, Op2]
  ): Scan.Aux[j.Out, I, O, (SA, SB)] =
    ScanComposition.andThen(left, right)
  
  /**
   * Fanout (&&&).
   */
  infix def &&&[Op2[_, _], O2, SB](right: Scan.Aux[Op2, I, O2, SB])(
    using j: Joiner[Op1, Op2]
  ): Scan.Aux[j.Out, I, (M, O2), (SA, SB)] =
    ScanComposition.fanout(left, right)
