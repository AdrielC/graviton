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
      case FreeScan.Prim(init, step, flush) =>
        val s0 = InitF.interpret(init)
        val outputs = step.run(inputs)
        // Extract state and outputs from Chunk[(S, O)]
        if (outputs.isEmpty) then
          val flushOpts = flush(s0)
          val finalOuts = flushOpts.collect { case Some(o) => o }
          (s0, finalOuts)
        else
          val finalState = outputs.last._1
          val outs = outputs.map(_._2)
          val flushOpts = flush(finalState)
          val flushResults = flushOpts.collect { case Some(o) => o }
          (finalState, outs ++ flushResults)
      
      case FreeScan.Seq(left, right) =>
        val (sLeft, outsLeft) = runChunk(left, inputs)
        val (sRight, outsRight) = runChunk(right, outsLeft)
        // Concatenate states
        val finalState = concatStates(sLeft, sRight)
        (finalState.asInstanceOf[S], outsRight)
      
      case FreeScan.Dimap(base, l, r) =>
        val mappedInputs = inputs.map(l)
        val (state, outs) = runChunk(base, mappedInputs)
        (state.asInstanceOf[S], outs.map(r))
      
      case par: FreeScan.Par[Chunk, Chunk, i1, i2, o1, o2, sa, sb] =>
        val pairs = inputs.asInstanceOf[Chunk[(i1, i2)]]
        val (inputs1, inputs2) = pairs.unzip
        val (sa2, outsa) = runChunk[i1, o1, sa](par.a, inputs1)
        val (sb2, outsb) = runChunk[i2, o2, sb](par.b, inputs2)
        val mergedState = mergeStates(sa2, sb2)
        val paired = outsa.zip(outsb)
        (mergedState.asInstanceOf[S], paired.asInstanceOf[Chunk[O]])
      
      case choice: FreeScan.Choice[Chunk, Chunk, il, ir, ol, or, sl, sr] =>
        val inputsEither = inputs.asInstanceOf[Chunk[Either[il, ir]]]
        val lefts = inputsEither.collect { case Left(x) => x }
        val rights = inputsEither.collect { case Right(x) => x }
        
        val (sl2, outsl) = runChunk[il, ol, sl](choice.l, lefts)
        val (sr2, outsr) = runChunk[ir, or, sr](choice.r, rights)
        val mergedState = mergeStates(sl2, sr2)
        val outs = outsl.map(Left(_)) ++ outsr.map(Right(_))
        (mergedState.asInstanceOf[S], outs.asInstanceOf[Chunk[O]])
      
      case fanout @ FreeScan.Fanout(a, b) =>
        val (sa, outsa) = runChunk(a, inputs)
        val (sb, outsb) = runChunk(b, inputs)
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
      case FreeScan.Prim(init, step, flush) =>
        val s0 = InitF.interpret(init)
        val (state, output) = step.run(input)
        (state, output)
      
      case FreeScan.Seq(left, right) =>
        val (sLeft, outLeft) = runId(left, input)
        val (sRight, outRight) = runId(right, outLeft)
        val finalState = concatStates(sLeft, sRight)
        (finalState.asInstanceOf[S], outRight)
      
      case FreeScan.Dimap(base, l, r) =>
        val (state, out) = runId(base, l(input))
        (state.asInstanceOf[S], r(out))
      
      case par: FreeScan.Par[Id, Id, i1, i2, o1, o2, sa, sb] =>
        val (i1val, i2val) = input.asInstanceOf[(i1, i2)]
        val (sa2, oa) = runId[i1, o1, sa](par.a, i1val)
        val (sb2, ob) = runId[i2, o2, sb](par.b, i2val)
        val mergedState = mergeStates(sa2, sb2)
        (mergedState.asInstanceOf[S], (oa, ob).asInstanceOf[O])
      
      case choice: FreeScan.Choice[Id, Id, il, ir, ol, or, sl, sr] =>
        input.asInstanceOf[Either[il, ir]] match
          case Left(x) =>
            val (sl2, ol2) = runId[il, ol, sl](choice.l, x)
            (sl2.asInstanceOf[S], Left(ol2).asInstanceOf[O])
          case Right(x) =>
            val (sr2, or2) = runId[ir, or, sr](choice.r, x)
            (sr2.asInstanceOf[S], Right(or2).asInstanceOf[O])
      
      case fanout @ FreeScan.Fanout(a, b) =>
        val (sa, oa) = runId(a, input)
        val (sb, ob) = runId(b, input)
        val mergedState = mergeStates(sa, sb)
        (mergedState.asInstanceOf[S], (oa, ob).asInstanceOf[O])
  
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
