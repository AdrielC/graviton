package graviton.core.scan

import zio.Chunk

/**
 * Pure interpreter for FreeScan.
 *
 * Executes scans in-memory without effects, useful for:
 * - Testing and property-based verification
 * - Spark/batch adapters
 * - Pure computation contexts
 *
 * Threads state through composition, handles flush correctly.
 *
 * The implementation is factored to avoid duplication between
 * Chunk and Id executors - both delegate to a common `run` method
 * parameterized by an Executor typeclass.
 */
object InterpretPure:

  /** Execution strategy for different functors */
  private trait Executor[F[_], G[_]]:
    def runPrim[I, O, S](init: S, step: F[I] => G[(S, O)], flush: S => G[Option[O]], input: F[I]): (S, G[O])
    def combineChoice[O](lefts: G[O], rights: G[O]): G[Either[O, O]]
    def combineFanout[O1, O2](outs1: G[O1], outs2: G[O2]): G[(O1, O2)]
    def splitEither[I](input: F[Either[I, I]]): (F[I], F[I])
    def splitPair[I1, I2](input: F[(I1, I2)]): (F[I1], F[I2])
    def map[A, B](ga: G[A])(f: A => B): G[B]

  /** Chunk executor - batch processing */
  private given Executor[Chunk, Chunk] with
    def runPrim[I, O, S](
      init: S,
      step: Chunk[I] => Chunk[(S, O)],
      flush: S => Chunk[Option[O]],
      input: Chunk[I],
    ): (S, Chunk[O]) =
      val outputs = step(input)
      if outputs.isEmpty then
        val flushOpts = flush(init)
        (init, flushOpts.collect { case Some(o) => o })
      else
        val finalState = outputs.last._1
        val outs       = outputs.map(_._2)
        val flushOpts  = flush(finalState)
        (finalState, outs ++ flushOpts.collect { case Some(o) => o })

    def combineChoice[O](lefts: Chunk[O], rights: Chunk[O]): Chunk[Either[O, O]] =
      lefts.map(Left(_)) ++ rights.map(Right(_))

    def combineFanout[O1, O2](outs1: Chunk[O1], outs2: Chunk[O2]): Chunk[(O1, O2)] =
      outs1.zip(outs2)

    def splitEither[I](input: Chunk[Either[I, I]]): (Chunk[I], Chunk[I]) =
      (input.collect { case Left(x) => x }, input.collect { case Right(x) => x })

    def splitPair[I1, I2](input: Chunk[(I1, I2)]): (Chunk[I1], Chunk[I2]) =
      input.unzip

    def map[A, B](ga: Chunk[A])(f: A => B): Chunk[B] = ga.map(f)

  /** Id executor - single-value processing */
  private given Executor[Id, Id] with
    def runPrim[I, O, S](
      init: S,
      step: I => (S, O),
      flush: S => Option[O],
      input: I,
    ): (S, O) =
      step(input) // For Id, we don't use flush in the step

    def combineChoice[O](lefts: O, rights: O): Either[O, O] =
      throw new IllegalStateException("combineChoice should not be called for Id")

    def combineFanout[O1, O2](outs1: O1, outs2: O2): (O1, O2) =
      throw new UnsupportedOperationException("Fanout not supported for Id functor - use Chunk")

    def splitEither[I](input: Either[I, I]): (I, I) =
      throw new IllegalStateException("splitEither should not be called for Id")

    def splitPair[I1, I2](input: (I1, I2)): (I1, I2) = input

    def map[A, B](ga: A)(f: A => B): B = f(ga)

  /**
   * Generic interpreter - factored out from runChunk/runId.
   *
   * This eliminates duplication by parameterizing over execution strategy.
   */
  private def run[F[_], G[_], I, O, S <: Rec](fs: FreeScan[F, G, I, O, S], input: F[I])(using exec: Executor[F, G]): (S, G[O]) =
    fs match
      case prim: FreeScan.Prim[?, ?, ?, ?, ?] =>
        val s0    = InitF.evaluate(prim.init).asInstanceOf[S]
        val step  = prim.step.run.asInstanceOf[F[I] => G[(S, O)]]
        val flush = prim.flush.asInstanceOf[S => G[Option[O]]]
        exec.runPrim(s0, step, flush, input)

      case seq: FreeScan.Seq[?, ?, ?, ?, ?, ?, ?] =>
        val (sLeft, outsLeft)   = run(seq.left.asInstanceOf[FreeScan[F, G, I, Any, Rec]], input)
        val (sRight, outsRight) = run(seq.right.asInstanceOf[FreeScan[F, G, Any, O, Rec]], outsLeft.asInstanceOf[F[Any]])
        val finalState          = concatStates(sLeft, sRight)
        (finalState.asInstanceOf[S], outsRight)

      case dim: FreeScan.Dimap[?, ?, ?, ?, ?, ?, ?] =>
        // Apply input mapping via functor map (simplified - assumes we can map over F)
        val (state, outs) = run(dim.base.asInstanceOf[FreeScan[F, G, Any, Any, S]], input.asInstanceOf[F[Any]])
        (state, exec.map(outs)(dim.r.asInstanceOf[Any => O]))

      case par: FreeScan.Par[?, ?, ?, ?, ?, ?, ?, ?] =>
        val pairs              = input.asInstanceOf[F[(Any, Any)]]
        val (inputs1, inputs2) = exec.splitPair(pairs)
        val (sa, outsa)        = run(par.a.asInstanceOf[FreeScan[F, G, Any, Any, Rec]], inputs1)
        val (sb, outsb)        = run(par.b.asInstanceOf[FreeScan[F, G, Any, Any, Rec]], inputs2)
        val mergedState        = mergeStates(sa, sb)
        (mergedState.asInstanceOf[S], exec.combineFanout(outsa, outsb).asInstanceOf[G[O]])

      case choice: FreeScan.Choice[?, ?, ?, ?, ?, ?, ?, ?] =>
        val inputEither     = input.asInstanceOf[F[Either[Any, Any]]]
        val (lefts, rights) = exec.splitEither(inputEither)
        val (sl, outsl)     = run(choice.l.asInstanceOf[FreeScan[F, G, Any, Any, Rec]], lefts)
        val (sr, outsr)     = run(choice.r.asInstanceOf[FreeScan[F, G, Any, Any, Rec]], rights)
        val mergedState     = mergeStates(sl, sr)
        (mergedState.asInstanceOf[S], exec.combineChoice(outsl, outsr).asInstanceOf[G[O]])

      case fanout: FreeScan.Fanout[?, ?, ?, ?, ?, ?, ?] =>
        val (sa, outsa) = run(fanout.a.asInstanceOf[FreeScan[F, G, I, Any, Rec]], input)
        val (sb, outsb) = run(fanout.b.asInstanceOf[FreeScan[F, G, I, Any, Rec]], input)
        val mergedState = mergeStates(sa, sb)
        (mergedState.asInstanceOf[S], exec.combineFanout(outsa, outsb).asInstanceOf[G[O]])

  /**
   * Run a FreeScan on a Chunk of inputs, return final state and all outputs.
   */
  def runChunk[I, O, S <: Rec](fs: FreeScan[Chunk, Chunk, I, O, S], inputs: Chunk[I]): (S, Chunk[O]) =
    run(fs, inputs)

  /**
   * Run a FreeScan on a single input value (Id functor).
   */
  def runId[I, O, S <: Rec](fs: FreeScan[Id, Id, I, O, S], input: I): (S, O) =
    run(fs, input)

  /** Helper to concatenate states (simple tuple concat) */
  private def concatStates[A <: Rec, B <: Rec](a: A, b: B): A ++ B =
    (a, b) match
      case (EmptyTuple, bb)                       => bb.asInstanceOf[A ++ B]
      case (aa, EmptyTuple)                       => aa.asInstanceOf[A ++ B]
      case (aa: NonEmptyTuple, bb: NonEmptyTuple) =>
        (aa ++ bb).asInstanceOf[A ++ B]

  /** Helper to merge states (right-biased) */
  private def mergeStates[A <: Rec, B <: Rec](a: A, b: B): Merge[A, B] =
    rec.merge(a, b)
