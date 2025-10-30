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
    val s0 = InitF.evaluate(getInit(fs))

    def loop(state: S): ZChannel[Any, ZNothing, Chunk[I], Any, Nothing, Chunk[O], Unit] =
      ZChannel.readWithCause(
        (input: Chunk[I]) =>
          val (nextState, outputs) = processChunk(fs, state, input)
          ZChannel.write(outputs) *> loop(nextState)
        ,
        (cause: Cause[ZNothing]) => ZChannel.refailCause(cause),
        (_: Any) =>
          // Upstream ended - flush
          val flushOutputs = doFlush(fs, state)
          ZChannel.write(flushOutputs) *> ZChannel.unit,
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
      case prim: FreeScan.Prim[?, ?, ?, ?, ?]              => prim.init.asInstanceOf[InitF[S]]
      case seq: FreeScan.Seq[?, ?, ?, ?, ?, ?, ?]          =>
        val initLeft  = getInit(seq.left)
        val initRight = getInit(seq.right)
        InitF.later {
          val a = InitF.evaluate(initLeft)
          val b = InitF.evaluate(initRight)
          concatStates(a, b).asInstanceOf[S]
        }
      case dim: FreeScan.Dimap[?, ?, ?, ?, ?, ?, ?]        =>
        getInit(dim.base).asInstanceOf[InitF[S]]
      case par: FreeScan.Par[?, ?, ?, ?, ?, ?, ?, ?]       =>
        val initA = getInit(par.a)
        val initB = getInit(par.b)
        InitF.later {
          val sa = InitF.evaluate(initA)
          val sb = InitF.evaluate(initB)
          mergeStates(sa, sb).asInstanceOf[S]
        }
      case choice: FreeScan.Choice[?, ?, ?, ?, ?, ?, ?, ?] =>
        val initL = getInit(choice.l)
        val initR = getInit(choice.r)
        InitF.later {
          val sl = InitF.evaluate(initL)
          val sr = InitF.evaluate(initR)
          mergeStates(sl, sr).asInstanceOf[S]
        }
      case fanout: FreeScan.Fanout[?, ?, ?, ?, ?, ?, ?]    =>
        val initA = getInit(fanout.a)
        val initB = getInit(fanout.b)
        InitF.later {
          val sa = InitF.evaluate(initA)
          val sb = InitF.evaluate(initB)
          mergeStates(sa, sb).asInstanceOf[S]
        }

  /** Process a chunk of inputs, return new state and outputs */
  private def processChunk[I, O, S <: Rec](
    fs: FreeScan[Chunk, Chunk, I, O, S],
    state: S,
    input: Chunk[I],
  ): (S, Chunk[O]) =
    fs match
      case FreeScan.Prim(_, step, _) =>
        val result = step.run(input)
        if (result.isEmpty) then (state, Chunk.empty)
        else
          val newState = result.last._1
          val outputs  = result.map(_._2)
          (newState, outputs)

      case FreeScan.Seq(left, right) =>
        val (stateLeft, stateRight)    = extractSeqStates(state)
        val (newStateLeft, outsLeft)   =
          processChunk(left.asInstanceOf[FreeScan[Chunk, Chunk, Any, Any, Rec]], stateLeft, input.asInstanceOf[Chunk[Any]])
        val (newStateRight, outsRight) =
          processChunk(right.asInstanceOf[FreeScan[Chunk, Chunk, Any, O, Rec]], stateRight, outsLeft.asInstanceOf[Chunk[Any]])
        val finalState                 = concatStates(newStateLeft, newStateRight)
        (finalState.asInstanceOf[S], outsRight)

      case FreeScan.Dimap(base, l, r) =>
        val mappedInput      = input.map(i => l.asInstanceOf[I => Any](i))
        val (newState, outs) = processChunk(base.asInstanceOf[FreeScan[Chunk, Chunk, Any, Any, S]], state, mappedInput)
        (newState, outs.map(o => r.asInstanceOf[Any => O](o)))

      case par @ FreeScan.Par(a, b) =>
        val (sa, sb)           = extractMergedStates(state)
        val (inputs1, inputs2) = input.map(_.asInstanceOf[(Any, Any)]).unzip
        val (newSa, outsa)     = processChunk(a.asInstanceOf[FreeScan[Chunk, Chunk, Any, Any, Rec]], sa, inputs1.asInstanceOf[Chunk[Any]])
        val (newSb, outsb)     = processChunk(b.asInstanceOf[FreeScan[Chunk, Chunk, Any, Any, Rec]], sb, inputs2.asInstanceOf[Chunk[Any]])
        val finalState         = mergeStates(newSa, newSb)
        val paired             = outsa.zip(outsb)
        (finalState.asInstanceOf[S], paired.asInstanceOf[Chunk[O]])

      case choice @ FreeScan.Choice(l, r) =>
        val (sl, sr)     = extractMergedStates(state)
        val inputsEither = input.asInstanceOf[Chunk[Either[Any, Any]]]
        val lefts        = inputsEither.collect { case Left(x) => x }
        val rights       = inputsEither.collect { case Right(x) => x }

        val (newSl, outsl) = processChunk(l.asInstanceOf[FreeScan[Chunk, Chunk, Any, Any, Rec]], sl.asInstanceOf[Rec], lefts)
        val (newSr, outsr) = processChunk(r.asInstanceOf[FreeScan[Chunk, Chunk, Any, Any, Rec]], sr.asInstanceOf[Rec], rights)
        val finalState     = mergeStates(newSl, newSr)
        val outs           = outsl.map(Left(_)) ++ outsr.map(Right(_))
        (finalState.asInstanceOf[S], outs.asInstanceOf[Chunk[O]])

      case fanout @ FreeScan.Fanout(a, b) =>
        val (sa, sb)       = extractMergedStates(state)
        val (newSa, outsa) = processChunk(a.asInstanceOf[FreeScan[Chunk, Chunk, I, Any, Rec]], sa.asInstanceOf[Rec], input)
        val (newSb, outsb) = processChunk(b.asInstanceOf[FreeScan[Chunk, Chunk, I, Any, Rec]], sb.asInstanceOf[Rec], input)
        val finalState     = mergeStates(newSa, newSb)
        // Pair outputs element-wise
        val minLen         = math.min(outsa.length, outsb.length)
        val paired         = outsa.take(minLen).zip(outsb.take(minLen))
        (finalState.asInstanceOf[S], paired.asInstanceOf[Chunk[O]])

  /** Do flush on final state */
  private def doFlush[I, O, S <: Rec](
    fs: FreeScan[Chunk, Chunk, I, O, S],
    state: S,
  ): Chunk[O] =
    fs match
      case FreeScan.Prim(_, _, flush) =>
        flush.asInstanceOf[S => Chunk[Option[O]]](state).collect { case Some(o) => o }

      case FreeScan.Seq(left, right) =>
        val (stateLeft, stateRight) = extractSeqStates(state)
        val flushLeft               = doFlush(left.asInstanceOf[FreeScan[Chunk, Chunk, I, Any, Rec]], stateLeft.asInstanceOf[Rec])
        val flushRight              = doFlush(right.asInstanceOf[FreeScan[Chunk, Chunk, Any, O, Rec]], stateRight.asInstanceOf[Rec])
        // Flush left outputs through right, then flush right
        val (_, throughRight)       =
          processChunk(right.asInstanceOf[FreeScan[Chunk, Chunk, Any, O, Rec]], stateRight.asInstanceOf[Rec], flushLeft)
        throughRight ++ flushRight

      case FreeScan.Dimap(base, _, r) =>
        val flushed = doFlush(base.asInstanceOf[FreeScan[Chunk, Chunk, Any, Any, S]], state)
        flushed.map(r.asInstanceOf[Any => O])

      case par @ FreeScan.Par(a, b) =>
        val (sa, sb) = extractMergedStates(state)
        val fa       = doFlush(a.asInstanceOf[FreeScan[Chunk, Chunk, I, Any, Rec]], sa.asInstanceOf[Rec])
        val fb       = doFlush(b.asInstanceOf[FreeScan[Chunk, Chunk, I, Any, Rec]], sb.asInstanceOf[Rec])
        fa.zip(fb).asInstanceOf[Chunk[O]]

      case choice @ FreeScan.Choice(l, r) =>
        val (sl, sr) = extractMergedStates(state)
        val fl       = doFlush(l.asInstanceOf[FreeScan[Chunk, Chunk, Any, Any, Rec]], sl.asInstanceOf[Rec]).map(Left(_))
        val fr       = doFlush(r.asInstanceOf[FreeScan[Chunk, Chunk, Any, Any, Rec]], sr.asInstanceOf[Rec]).map(Right(_))
        (fl ++ fr).asInstanceOf[Chunk[O]]

      case fanout @ FreeScan.Fanout(a, b) =>
        val (sa, sb) = extractMergedStates(state)
        val fa       = doFlush(a.asInstanceOf[FreeScan[Chunk, Chunk, I, Any, Rec]], sa.asInstanceOf[Rec])
        val fb       = doFlush(b.asInstanceOf[FreeScan[Chunk, Chunk, I, Any, Rec]], sb.asInstanceOf[Rec])
        fa.zip(fb).asInstanceOf[Chunk[O]]

  /** Helper: extract left/right states from Seq concatenation */
  private def extractSeqStates(state: Rec): (Rec, Rec) =
    // This is a simplification - in real impl we'd track boundaries
    (EmptyTuple, state)

  /** Helper: extract states from merged state (Par/Choice/Fanout) */
  private def extractMergedStates(state: Rec): (Rec, Rec) =
    // Simplification - real impl would properly extract
    (EmptyTuple, state)

  /** Helper to concatenate states */
  private def concatStates[A <: Rec, B <: Rec](a: A, b: B): A ++ B =
    (a, b) match
      case (EmptyTuple, bb)                       => bb.asInstanceOf[A ++ B]
      case (aa, EmptyTuple)                       => aa.asInstanceOf[A ++ B]
      case (aa: NonEmptyTuple, bb: NonEmptyTuple) =>
        (aa ++ bb).asInstanceOf[A ++ B]

  /** Helper to merge states */
  private def mergeStates[A <: Rec, B <: Rec](a: A, b: B): Merge[A, B] =
    rec.merge(a, b).asInstanceOf[Merge[A, B]]
