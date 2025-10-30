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
 */
object InterpretPure:

  /**
   * Run a FreeScan on a list of inputs, return final state and all outputs.
   *
   * For Chunk-based scans, we interpret F[_]=Chunk and G[_]=Chunk.
   */
  def runChunk[I, O, S <: Rec](
    fs: FreeScan[Chunk, Chunk, I, O, S],
    inputs: Chunk[I],
  ): (S, Chunk[O]) =
    fs match
      case prim: FreeScan.Prim[?, ?, ?, ?, ?] =>
        val s0      = InitF.evaluate(prim.init).asInstanceOf[S]
        val outputs = prim.step.run.asInstanceOf[Chunk[I] => Chunk[(S, O)]](inputs)
        // Extract state and outputs from Chunk[(S, O)]
        if (outputs.isEmpty) then
          val flushOpts = prim.flush.asInstanceOf[S => Chunk[Option[O]]](s0)
          val finalOuts = flushOpts.collect { case Some(o) => o }
          (s0, finalOuts)
        else
          val finalState   = outputs.last._1
          val outs         = outputs.map(_._2)
          val flushOpts    = prim.flush.asInstanceOf[S => Chunk[Option[O]]](finalState)
          val flushResults = flushOpts.collect { case Some(o) => o }
          (finalState, outs ++ flushResults)

      case seq: FreeScan.Seq[?, ?, ?, ?, ?, ?, ?] =>
        val (sLeft, outsLeft)   = runChunk(seq.left.asInstanceOf[FreeScan[Chunk, Chunk, I, Any, Rec]], inputs)
        val (sRight, outsRight) = runChunk(seq.right.asInstanceOf[FreeScan[Chunk, Chunk, Any, O, Rec]], outsLeft)
        val finalState          = concatStates(sLeft, sRight)
        (finalState.asInstanceOf[S], outsRight)

      case dim: FreeScan.Dimap[?, ?, ?, ?, ?, ?, ?] =>
        val mappedInputs  = inputs.map(dim.l.asInstanceOf[I => Any])
        val (state, outs) = runChunk(dim.base.asInstanceOf[FreeScan[Chunk, Chunk, Any, Any, S]], mappedInputs)
        (state, outs.map(dim.r.asInstanceOf[Any => O]))

      case par: FreeScan.Par[?, ?, ?, ?, ?, ?, ?, ?] =>
        val pairs              = inputs.asInstanceOf[Chunk[(Any, Any)]]
        val (inputs1, inputs2) = pairs.unzip
        val (sa2, outsa)       = runChunk(par.a.asInstanceOf[FreeScan[Chunk, Chunk, Any, Any, Rec]], inputs1)
        val (sb2, outsb)       = runChunk(par.b.asInstanceOf[FreeScan[Chunk, Chunk, Any, Any, Rec]], inputs2)
        val mergedState        = mergeStates(sa2, sb2)
        val paired             = outsa.zip(outsb)
        (mergedState.asInstanceOf[S], paired.asInstanceOf[Chunk[O]])

      case choice: FreeScan.Choice[?, ?, ?, ?, ?, ?, ?, ?] =>
        val inputsEither = inputs.asInstanceOf[Chunk[Either[Any, Any]]]
        val lefts        = inputsEither.collect { case Left(x) => x }
        val rights       = inputsEither.collect { case Right(x) => x }

        val (sl2, outsl) = runChunk(choice.l.asInstanceOf[FreeScan[Chunk, Chunk, Any, Any, Rec]], lefts)
        val (sr2, outsr) = runChunk(choice.r.asInstanceOf[FreeScan[Chunk, Chunk, Any, Any, Rec]], rights)
        val mergedState  = mergeStates(sl2, sr2)
        val outs         = outsl.map(Left(_).asInstanceOf[O]) ++ outsr.map(Right(_).asInstanceOf[O])
        (mergedState.asInstanceOf[S], outs)

      case fanout: FreeScan.Fanout[?, ?, ?, ?, ?, ?, ?] =>
        val (sa, outsa) = runChunk(fanout.a.asInstanceOf[FreeScan[Chunk, Chunk, I, Any, Rec]], inputs)
        val (sb, outsb) = runChunk(fanout.b.asInstanceOf[FreeScan[Chunk, Chunk, I, Any, Rec]], inputs)
        val mergedState = mergeStates(sa, sb)
        val paired      = outsa.zip(outsb)
        (mergedState.asInstanceOf[S], paired.asInstanceOf[Chunk[O]])

  /**
   * Run on Id functor (single values)
   */
  def runId[I, O, S <: Rec](
    fs: FreeScan[Id, Id, I, O, S],
    input: I,
  ): (S, O) =
    fs match
      case prim: FreeScan.Prim[?, ?, ?, ?, ?] =>
        val s0              = InitF.evaluate(prim.init).asInstanceOf[S]
        val (state, output) = prim.step.run.asInstanceOf[I => (S, O)](input)
        (state, output)

      case seq: FreeScan.Seq[?, ?, ?, ?, ?, ?, ?] =>
        val (sLeft, outLeft)   = runId(seq.left.asInstanceOf[FreeScan[Id, Id, I, Any, Rec]], input)
        val (sRight, outRight) = runId(seq.right.asInstanceOf[FreeScan[Id, Id, Any, O, Rec]], outLeft)
        val finalState         = concatStates(sLeft, sRight)
        (finalState.asInstanceOf[S], outRight)

      case dim: FreeScan.Dimap[?, ?, ?, ?, ?, ?, ?] =>
        val (state, out) = runId(dim.base.asInstanceOf[FreeScan[Id, Id, Any, Any, S]], dim.l.asInstanceOf[I => Any](input))
        (state, dim.r.asInstanceOf[Any => O](out))

      case par: FreeScan.Par[?, ?, ?, ?, ?, ?, ?, ?] =>
        val (i1val, i2val) = input.asInstanceOf[(Any, Any)]
        val (sa2, oa)      = runId(par.a.asInstanceOf[FreeScan[Id, Id, Any, Any, Rec]], i1val)
        val (sb2, ob)      = runId(par.b.asInstanceOf[FreeScan[Id, Id, Any, Any, Rec]], i2val)
        val mergedState    = mergeStates(sa2, sb2)
        (mergedState.asInstanceOf[S], (oa, ob).asInstanceOf[O])

      case choice: FreeScan.Choice[?, ?, ?, ?, ?, ?, ?, ?] =>
        input.asInstanceOf[Either[Any, Any]] match
          case Left(x)  =>
            val (sl2, ol2) = runId(choice.l.asInstanceOf[FreeScan[Id, Id, Any, Any, Rec]], x)
            (sl2.asInstanceOf[S], Left(ol2).asInstanceOf[O])
          case Right(x) =>
            val (sr2, or2) = runId(choice.r.asInstanceOf[FreeScan[Id, Id, Any, Any, Rec]], x)
            (sr2.asInstanceOf[S], Right(or2).asInstanceOf[O])

      case fanout: FreeScan.Fanout[?, ?, ?, ?, ?, ?, ?] =>
        // Fanout for Id is complex - use runChunk for fanout patterns
        throw new UnsupportedOperationException("Fanout not supported for Id functor - use Chunk")

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
