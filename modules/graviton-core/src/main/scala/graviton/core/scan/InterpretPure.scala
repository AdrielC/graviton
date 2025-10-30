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
    inputs: Chunk[I]
  ): (S, Chunk[O]) =
    fs match
      case prim: FreeScan.Prim[?, ?, ?, ?, ?] =>
        val s0 = InitF.evaluate(prim.init)
        val outputs = prim.step.run(inputs)
        // Extract state and outputs from Chunk[(S, O)]
        if (outputs.isEmpty) then
          val flushOpts = prim.flush(s0)
          val finalOuts = flushOpts.collect { case Some(o) => o }
          (s0.asInstanceOf[S], finalOuts.asInstanceOf[Chunk[O]])
        else
          val finalState = outputs.last._1
          val outs = outputs.map(_._2)
          val flushOpts = prim.flush(finalState)
          val flushResults = flushOpts.collect { case Some(o) => o }
          (finalState.asInstanceOf[S], (outs ++ flushResults).asInstanceOf[Chunk[O]])
      
      case seq: FreeScan.Seq[?, ?, ?, ?, ?, ?, ?, ?] =>
        val (sLeft, outsLeft) = runChunk(seq.left, inputs)
        val (sRight, outsRight) = runChunk(seq.right, outsLeft.asInstanceOf[Chunk[Any]])
        val finalState = concatStates(sLeft, sRight)
        (finalState.asInstanceOf[S], outsRight.asInstanceOf[Chunk[O]])
      
      case dim: FreeScan.Dimap[?, ?, ?, ?, ?, ?, ?] =>
        val mappedInputs = inputs.map(dim.l)
        val (state, outs) = runChunk(dim.base, mappedInputs)
        (state.asInstanceOf[S], outs.map(dim.r).asInstanceOf[Chunk[O]])
      
      case par: FreeScan.Par[?, ?, ?, ?, ?, ?, ?, ?] =>
        val pairs = inputs.asInstanceOf[Chunk[(Any, Any)]]
        val (inputs1, inputs2) = pairs.unzip
        val (sa2, outsa) = runChunk(par.a, inputs1.asInstanceOf[Chunk[Any]])
        val (sb2, outsb) = runChunk(par.b, inputs2.asInstanceOf[Chunk[Any]])
        val mergedState = mergeStates(sa2, sb2)
        val paired = outsa.zip(outsb)
        (mergedState.asInstanceOf[S], paired.asInstanceOf[Chunk[O]])
      
      case choice: FreeScan.Choice[?, ?, ?, ?, ?, ?, ?, ?] =>
        val inputsEither = inputs.asInstanceOf[Chunk[Either[Any, Any]]]
        val lefts = inputsEither.collect { case Left(x) => x }
        val rights = inputsEither.collect { case Right(x) => x }
        
        val (sl2, outsl) = runChunk(choice.l, lefts.asInstanceOf[Chunk[Any]])
        val (sr2, outsr) = runChunk(choice.r, rights.asInstanceOf[Chunk[Any]])
        val mergedState = mergeStates(sl2, sr2)
        val outs = outsl.map(Left(_)) ++ outsr.map(Right(_))
        (mergedState.asInstanceOf[S], outs.asInstanceOf[Chunk[O]])
      
      case fanout: FreeScan.Fanout[?, ?, ?, ?, ?, ?, ?] =>
        val (sa, outsa) = runChunk(fanout.a, inputs)
        val (sb, outsb) = runChunk(fanout.b, inputs)
        val mergedState = mergeStates(sa, sb)
        val paired = outsa.zip(outsb)
        (mergedState.asInstanceOf[S], paired.asInstanceOf[Chunk[O]])
  
  /**
   * Run on Id functor (single values)
   */
  def runId[I, O, S <: Rec](
    fs: FreeScan[Id, Id, I, O, S],
    input: I
  ): (S, O) =
    fs match
      case prim: FreeScan.Prim[?, ?, ?, ?, ?] =>
        val s0 = InitF.evaluate(prim.init)
        val (state, output) = prim.step.run(input)
        (state.asInstanceOf[S], output.asInstanceOf[O])
      
      case seq: FreeScan.Seq[?, ?, ?, ?, ?, ?, ?, ?] =>
        val (sLeft, outLeft) = runId(seq.left, input)
        val (sRight, outRight) = runId(seq.right, outLeft.asInstanceOf[Any])
        val finalState = concatStates(sLeft, sRight)
        (finalState.asInstanceOf[S], outRight.asInstanceOf[O])
      
      case dim: FreeScan.Dimap[?, ?, ?, ?, ?, ?, ?] =>
        val (state, out) = runId(dim.base, dim.l(input))
        (state.asInstanceOf[S], dim.r(out).asInstanceOf[O])
      
      case par: FreeScan.Par[?, ?, ?, ?, ?, ?, ?, ?] =>
        val (i1val, i2val) = input.asInstanceOf[(Any, Any)]
        val (sa2, oa) = runId(par.a, i1val)
        val (sb2, ob) = runId(par.b, i2val)
        val mergedState = mergeStates(sa2, sb2)
        (mergedState.asInstanceOf[S], (oa, ob).asInstanceOf[O])
      
      case choice: FreeScan.Choice[?, ?, ?, ?, ?, ?, ?, ?] =>
        input.asInstanceOf[Either[Any, Any]] match
          case Left(x) =>
            val (sl2, ol2) = runId(choice.l, x)
            (sl2.asInstanceOf[S], Left(ol2).asInstanceOf[O])
          case Right(x) =>
            val (sr2, or2) = runId(choice.r, x)
            (sr2.asInstanceOf[S], Right(or2).asInstanceOf[O])
      
      case fanout: FreeScan.Fanout[?, ?, ?, ?, ?, ?, ?] =>
        // Fanout for Id is complex - use runChunk for fanout patterns
        throw new UnsupportedOperationException("Fanout not supported for Id functor - use Chunk")
  
  /** Helper to concatenate states (simple tuple concat) */
  private def concatStates[A <: Rec, B <: Rec](a: A, b: B): A ++ B =
    (a, b) match
      case (EmptyTuple, bb) => bb.asInstanceOf[A ++ B]
      case (aa, EmptyTuple) => aa.asInstanceOf[A ++ B]
      case (aa: NonEmptyTuple, bb: NonEmptyTuple) =>
        (aa ++ bb).asInstanceOf[A ++ B]
  
  /** Helper to merge states (right-biased) */
  private def mergeStates[A <: Rec, B <: Rec](a: A, b: B): Merge[A, B] =
    rec.merge(a, b)
