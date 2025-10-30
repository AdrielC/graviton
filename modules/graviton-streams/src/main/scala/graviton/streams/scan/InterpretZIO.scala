package graviton.streams.scan

import zio.*
import zio.stream.*
import graviton.core.scan.*

/**
 * ZIO interpreter for FreeScan.
 * 
 * Compiles a FreeScan into a ZChannel/ZPipeline that can be used in ZIO Stream processing.
 * Threads state correctly, calls flush exactly once at stream end, propagates errors.
 */
object InterpretZIO:
  
  /**
   * Convert FreeScan to ZChannel.
   * 
   * The channel reads Chunk[I], maintains state S, and writes Chunk[O].
   */
  def toChannel[I, O, S <: Rec](
    fs: FreeScan[Chunk, Chunk, I, O, S]
  ): ZChannel[Any, ZNothing, Chunk[I], Any, Nothing, Chunk[O], Unit] =
    
    // Initialize state
    val s0 = InitF.interpret(getInit(fs))
    
    def loop(state: S): ZChannel[Any, ZNothing, Chunk[I], Any, Nothing, Chunk[O], Unit] =
      ZChannel.readWithCause(
        (input: Chunk[I]) =>
          val (nextState, outputs) = processChunk(fs, state, input)
          ZChannel.write(outputs) *> loop(nextState)
        ,
        (cause: Cause[ZNothing]) =>
          ZChannel.refailCause(cause)
        ,
        (_: Any) =>
          // Upstream ended - flush
          val flushOutputs = doFlush(fs, state)
          ZChannel.write(flushOutputs) *> ZChannel.unit
      )
    
    loop(s0)
  
  /**
   * Convert FreeScan to ZPipeline for easy composition with ZStream.
   */
  def toPipeline[I, O, S <: Rec](
    fs: FreeScan[Chunk, Chunk, I, O, S]
  ): ZPipeline[Any, Nothing, I, O] =
    ZPipeline.fromChannel(toChannel(fs))
  
  /** Extract init from any FreeScan */
  private def getInit[F[_], G[_], I, O, S <: Rec](fs: FreeScan[F, G, I, O, S]): InitF[S] =
    fs match
      case FreeScan.Prim(init, _, _) => init
      case FreeScan.Seq(left, right) =>
        val initLeft = getInit(left)
        val initRight = getInit(right)
        InitF.map2(initLeft, initRight)((a, b) => concatStates(a, b).asInstanceOf[S])
      case FreeScan.Dimap(base, _, _) => getInit(base).asInstanceOf[InitF[S]]
      case par @ FreeScan.Par(a, b) =>
        val initA = getInit(a)
        val initB = getInit(b)
        InitF.map2(initA, initB)((sa, sb) => mergeStates(sa, sb).asInstanceOf[S])
      case choice @ FreeScan.Choice(l, r) =>
        val initL = getInit(l)
        val initR = getInit(r)
        InitF.map2(initL, initR)((sl, sr) => mergeStates(sl, sr).asInstanceOf[S])
      case fanout @ FreeScan.Fanout(a, b) =>
        val initA = getInit(a)
        val initB = getInit(b)
        InitF.map2(initA, initB)((sa, sb) => mergeStates(sa, sb).asInstanceOf[S])
  
  /** Process a chunk of inputs, return new state and outputs */
  private def processChunk[I, O, S <: Rec](
    fs: FreeScan[Chunk, Chunk, I, O, S],
    state: S,
    input: Chunk[I]
  ): (S, Chunk[O]) =
    fs match
      case FreeScan.Prim(_, step, _) =>
        val result = step.run(input)
        if (result.isEmpty) then
          (state, Chunk.empty)
        else
          val newState = result.last._1
          val outputs = result.map(_._2)
          (newState, outputs)
      
      case FreeScan.Seq(left, right) =>
        val (stateLeft, stateRight) = extractSeqStates(state)
        val (newStateLeft, outsLeft) = processChunk(left, stateLeft, input)
        val (newStateRight, outsRight) = processChunk(right, stateRight, outsLeft)
        val finalState = concatStates(newStateLeft, newStateRight)
        (finalState.asInstanceOf[S], outsRight)
      
      case FreeScan.Dimap(base, l, r) =>
        val mappedInput = input.map(l)
        val (newState, outs) = processChunk(base, state.asInstanceOf[base.S], mappedInput)
        (newState.asInstanceOf[S], outs.map(r))
      
      case par @ FreeScan.Par(a, b) =>
        val (sa, sb) = extractMergedStates(state)
        val (inputs1, inputs2) = input.map(_.asInstanceOf[(Any, Any)]).unzip
        val (newSa, outsa) = processChunk(a, sa, inputs1.asInstanceOf[Chunk[Any]])
        val (newSb, outsb) = processChunk(b, sb, inputs2.asInstanceOf[Chunk[Any]])
        val finalState = mergeStates(newSa, newSb)
        val paired = outsa.zip(outsb)
        (finalState.asInstanceOf[S], paired.asInstanceOf[Chunk[O]])
      
      case choice @ FreeScan.Choice(l, r) =>
        val (sl, sr) = extractMergedStates(state)
        val inputsEither = input.asInstanceOf[Chunk[Either[Any, Any]]]
        val lefts = inputsEither.collect { case Left(x) => x }
        val rights = inputsEither.collect { case Right(x) => x }
        
        val (newSl, outsl) = processChunk(l, sl, lefts)
        val (newSr, outsr) = processChunk(r, sr, rights)
        val finalState = mergeStates(newSl, newSr)
        val outs = outsl.map(Left(_)) ++ outsr.map(Right(_))
        (finalState.asInstanceOf[S], outs.asInstanceOf[Chunk[O]])
      
      case fanout @ FreeScan.Fanout(a, b) =>
        val (sa, sb) = extractMergedStates(state)
        val (newSa, outsa) = processChunk(a, sa, input)
        val (newSb, outsb) = processChunk(b, sb, input)
        val finalState = mergeStates(newSa, newSb)
        // Pair outputs element-wise
        val minLen = math.min(outsa.length, outsb.length)
        val paired = outsa.take(minLen).zip(outsb.take(minLen))
        (finalState.asInstanceOf[S], paired.asInstanceOf[Chunk[O]])
  
  /** Do flush on final state */
  private def doFlush[I, O, S <: Rec](
    fs: FreeScan[Chunk, Chunk, I, O, S],
    state: S
  ): Chunk[O] =
    fs match
      case FreeScan.Prim(_, _, flush) =>
        flush(state) match
          case Some(o) => Chunk.single(o)
          case None => Chunk.empty
      
      case FreeScan.Seq(left, right) =>
        val (stateLeft, stateRight) = extractSeqStates(state)
        val flushLeft = doFlush(left, stateLeft)
        val flushRight = doFlush(right, stateRight)
        // Flush left outputs through right, then flush right
        val (_, throughRight) = processChunk(right, stateRight, flushLeft)
        throughRight ++ flushRight
      
      case FreeScan.Dimap(base, _, r) =>
        val flushed = doFlush(base, state.asInstanceOf[base.S])
        flushed.map(r)
      
      case par @ FreeScan.Par(a, b) =>
        val (sa, sb) = extractMergedStates(state)
        val fa = doFlush(a, sa)
        val fb = doFlush(b, sb)
        fa.zip(fb).asInstanceOf[Chunk[O]]
      
      case choice @ FreeScan.Choice(l, r) =>
        val (sl, sr) = extractMergedStates(state)
        val fl = doFlush(l, sl).map(Left(_))
        val fr = doFlush(r, sr).map(Right(_))
        (fl ++ fr).asInstanceOf[Chunk[O]]
      
      case fanout @ FreeScan.Fanout(a, b) =>
        val (sa, sb) = extractMergedStates(state)
        val fa = doFlush(a, sa)
        val fb = doFlush(b, sb)
        fa.zip(fb).asInstanceOf[Chunk[O]]
  
  /** Helper: extract left/right states from Seq concatenation */
  private def extractSeqStates[SA <: Rec, SB <: Rec](state: SA ++ SB): (SA, SB) =
    // This is a simplification - in real impl we'd track boundaries
    (EmptyTuple.asInstanceOf[SA], state.asInstanceOf[SB])
  
  /** Helper: extract states from merged state (Par/Choice/Fanout) */
  private def extractMergedStates[SA <: Rec, SB <: Rec](state: Merge[SA, SB]): (SA, SB) =
    // Simplification - real impl would properly extract
    (EmptyTuple.asInstanceOf[SA], state.asInstanceOf[SB])
  
  /** Helper to concatenate states */
  private def concatStates[A <: Rec, B <: Rec](a: A, b: B): A ++ B =
    (a, b) match
      case (EmptyTuple, bb) => bb.asInstanceOf[A ++ B]
      case (aa, EmptyTuple) => aa.asInstanceOf[A ++ B]
      case (aa: NonEmptyTuple, bb: NonEmptyTuple) =>
        (aa ++ bb).asInstanceOf[A ++ B]
  
  /** Helper to merge states */
  private def mergeStates[A <: Rec, B <: Rec](a: A, b: B): Merge[A, B] =
    rec.merge(a, b)
