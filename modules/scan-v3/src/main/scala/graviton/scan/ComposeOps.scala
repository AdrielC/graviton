package graviton.scan

import kyo.*
import Scan.*

/** Runtime helpers that execute composed scans.
 *
 *  Factored out of the GADT nodes so that the GADT stays a pure data
 *  description. Uses Kyo's `<` extension methods (`.map` / `.flatMap`) to
 *  thread state through composed scans.
 */
private[scan] object ComposeOps:

  // ---------------- AndThen: state is ComposeState[SL, SR] ----------------

  def initAndThen[I, M, O, SL, SR, EL, ER](
    left: Aux[I, M, SL, EL],
    right: Aux[M, O, SR, ER],
  ): ComposeState[SL, SR] < (EL & ER) =
    initBoth[SL, SR, EL, ER](left, right)

  /** Generic two-sided init: does not constrain left and right's I/O to
   *  line up. Used by AndThen, Fanout, Product, and Choice.
   */
  def initBoth[SL, SR, EL, ER](
    left: Scan.Aux[?, ?, SL, EL],
    right: Scan.Aux[?, ?, SR, ER],
  ): ComposeState[SL, SR] < (EL & ER) =
    (isStatelessScan(left), isStatelessScan(right)) match
      case (true, true) =>
        nothingSentinel.asInstanceOf[ComposeState[SL, SR] < (EL & ER)]
      case (true, false) =>
        right.init.asInstanceOf[ComposeState[SL, SR] < (EL & ER)]
      case (false, true) =>
        left.init.asInstanceOf[ComposeState[SL, SR] < (EL & ER)]
      case (false, false) =>
        val combined: ComposeState[SL, SR] < (EL & ER) =
          left.init.flatMap { sl =>
            right.init.map { sr =>
              (sl, sr).asInstanceOf[ComposeState[SL, SR]]
            }
          }
        combined

  def stepAndThen[I, M, O, SL, SR, EL, ER](
    left: Aux[I, M, SL, EL],
    right: Aux[M, O, SR, ER],
    state: ComposeState[SL, SR],
    input: I,
  ): (ComposeState[SL, SR], Chunk[O]) < (EL & ER) =
    val (sl, sr) = splitState[SL, SR](left, right, state)
    val out: (ComposeState[SL, SR], Chunk[O]) < (EL & ER) =
      left.step(sl, input).flatMap { case (sl2, intermediates) =>
        foldStep[M, O, SR, ER](right, sr, intermediates).map { case (sr2, outs) =>
          (combineState[SL, SR](left, right, sl2, sr2), outs)
        }
      }
    out

  def flushAndThen[I, M, O, SL, SR, EL, ER](
    left: Aux[I, M, SL, EL],
    right: Aux[M, O, SR, ER],
    state: ComposeState[SL, SR],
  ): Chunk[O] < (EL & ER) =
    val (sl, sr) = splitState[SL, SR](left, right, state)
    val out: Chunk[O] < (EL & ER) =
      left.flush(sl).flatMap { leftFinals =>
        foldStep[M, O, SR, ER](right, sr, leftFinals).flatMap { case (sr2, midOuts) =>
          right.flush(sr2).map(finals => midOuts ++ finals)
        }
      }
    out

  def releaseAndThen[SL, SR, EL, ER](
    left: Scan.Aux[?, ?, SL, EL],
    right: Scan.Aux[?, ?, SR, ER],
    state: ComposeState[SL, SR],
  ): Unit < (EL & ER) =
    val (sl, sr) = splitState[SL, SR](left, right, state)
    val out: Unit < (EL & ER) =
      right.release(sr).flatMap(_ => left.release(sl))
    out

  // ---------------- Fanout: state is FanoutState[SL, SR, OL, OR] ----------

  def initFanout[I, OL, OR, SL, SR, EL, ER](
    left: Aux[I, OL, SL, EL],
    right: Aux[I, OR, SR, ER],
  ): FanoutState[SL, SR, OL, OR] < (EL & ER) =
    val out: FanoutState[SL, SR, OL, OR] < (EL & ER) =
      initBoth[SL, SR, EL, ER](left, right).map { pairedOrSingle =>
        val (sl, sr) = splitState[SL, SR](left, right, pairedOrSingle)
        combineFanoutState[SL, SR, OL, OR](
          left, right, sl, sr, Chunk.empty[OL], Chunk.empty[OR],
        )
      }
    out

  def stepFanout[I, OL, OR, SL, SR, EL, ER](
    left: Aux[I, OL, SL, EL],
    right: Aux[I, OR, SR, ER],
    state: FanoutState[SL, SR, OL, OR],
    input: I,
  ): (FanoutState[SL, SR, OL, OR], Chunk[(OL, OR)]) < (EL & ER) =
    val (sl, sr, bufL, bufR) = splitFanoutState[SL, SR, OL, OR](left, right, state)
    val out: (FanoutState[SL, SR, OL, OR], Chunk[(OL, OR)]) < (EL & ER) =
      left.step(sl, input).flatMap { case (sl2, lOuts) =>
        right.step(sr, input).map { case (sr2, rOuts) =>
          val combinedL = bufL ++ lOuts
          val combinedR = bufR ++ rOuts
          val zipLen    = math.min(combinedL.size, combinedR.size)
          val paired    = combinedL.take(zipLen).zip(combinedR.take(zipLen))
          val remL      = combinedL.drop(zipLen)
          val remR      = combinedR.drop(zipLen)
          (combineFanoutState[SL, SR, OL, OR](left, right, sl2, sr2, remL, remR), paired)
        }
      }
    out

  def flushFanout[I, OL, OR, SL, SR, EL, ER](
    left: Aux[I, OL, SL, EL],
    right: Aux[I, OR, SR, ER],
    state: FanoutState[SL, SR, OL, OR],
  ): Chunk[(OL, OR)] < (EL & ER) =
    val (sl, sr, bufL, bufR) = splitFanoutState[SL, SR, OL, OR](left, right, state)
    val out: Chunk[(OL, OR)] < (EL & ER) =
      left.flush(sl).flatMap { lFinal =>
        right.flush(sr).map { rFinal =>
          val combinedL = bufL ++ lFinal
          val combinedR = bufR ++ rFinal
          val zipLen    = math.min(combinedL.size, combinedR.size)
          combinedL.take(zipLen).zip(combinedR.take(zipLen))
        }
      }
    out

  def releaseFanout[I, OL, OR, SL, SR, EL, ER](
    left: Aux[I, OL, SL, EL],
    right: Aux[I, OR, SR, ER],
    state: FanoutState[SL, SR, OL, OR],
  ): Unit < (EL & ER) =
    val (sl, sr, _, _) = splitFanoutState[SL, SR, OL, OR](left, right, state)
    val out: Unit < (EL & ER) =
      right.release(sr).flatMap(_ => left.release(sl))
    out

  // ---------------- Product: state same as AndThen ------------------------

  def stepProduct[IL, IR, OL, OR, SL, SR, EL, ER](
    left: Aux[IL, OL, SL, EL],
    right: Aux[IR, OR, SR, ER],
    state: ComposeState[SL, SR],
    input: (IL, IR),
  ): (ComposeState[SL, SR], Chunk[(OL, OR)]) < (EL & ER) =
    val (sl, sr) = splitState[SL, SR](left, right, state)
    val (il, ir) = input
    val out: (ComposeState[SL, SR], Chunk[(OL, OR)]) < (EL & ER) =
      left.step(sl, il).flatMap { case (sl2, lOuts) =>
        right.step(sr, ir).map { case (sr2, rOuts) =>
          val zipLen = math.min(lOuts.size, rOuts.size)
          (combineState[SL, SR](left, right, sl2, sr2), lOuts.take(zipLen).zip(rOuts.take(zipLen)))
        }
      }
    out

  def flushProduct[IL, IR, OL, OR, SL, SR, EL, ER](
    left: Aux[IL, OL, SL, EL],
    right: Aux[IR, OR, SR, ER],
    state: ComposeState[SL, SR],
  ): Chunk[(OL, OR)] < (EL & ER) =
    val (sl, sr) = splitState[SL, SR](left, right, state)
    val out: Chunk[(OL, OR)] < (EL & ER) =
      left.flush(sl).flatMap { lFinal =>
        right.flush(sr).map { rFinal =>
          val zipLen = math.min(lFinal.size, rFinal.size)
          lFinal.take(zipLen).zip(rFinal.take(zipLen))
        }
      }
    out

  // ---------------- Choice: state same as AndThen -------------------------

  def stepChoice[IL, IR, OL, OR, SL, SR, EL, ER](
    left: Aux[IL, OL, SL, EL],
    right: Aux[IR, OR, SR, ER],
    state: ComposeState[SL, SR],
    input: Either[IL, IR],
  ): (ComposeState[SL, SR], Chunk[OL | OR]) < (EL & ER) =
    val (sl, sr) = splitState[SL, SR](left, right, state)
    input match
      case Left(il) =>
        val out: (ComposeState[SL, SR], Chunk[OL | OR]) < (EL & ER) =
          left.step(sl, il).map { case (sl2, outs) =>
            (combineState[SL, SR](left, right, sl2, sr), outs.asInstanceOf[Chunk[OL | OR]])
          }
        out
      case Right(ir) =>
        val out: (ComposeState[SL, SR], Chunk[OL | OR]) < (EL & ER) =
          right.step(sr, ir).map { case (sr2, outs) =>
            (combineState[SL, SR](left, right, sl, sr2), outs.asInstanceOf[Chunk[OL | OR]])
          }
        out

  def flushChoice[IL, IR, OL, OR, SL, SR, EL, ER](
    left: Aux[IL, OL, SL, EL],
    right: Aux[IR, OR, SR, ER],
    state: ComposeState[SL, SR],
  ): Chunk[OL | OR] < (EL & ER) =
    val (sl, sr) = splitState[SL, SR](left, right, state)
    val out: Chunk[OL | OR] < (EL & ER) =
      left.flush(sl).flatMap { lFinal =>
        right.flush(sr).map { rFinal =>
          (lFinal ++ rFinal).asInstanceOf[Chunk[OL | OR]]
        }
      }
    out

  // ---------------- Helpers -----------------------------------------------

  /** Phantom value for Nothing-typed states. Never inspected at runtime. */
  private val nothingSentinel: AnyRef = new AnyRef

  private def isStatelessScan(s: Scan[?, ?]): Boolean = s match
    case Id | _: Arr[?, ?] | _: FusedArr[?, ?] => true
    case _                                     => false

  private def splitState[SL, SR](
    left: Scan[?, ?],
    right: Scan[?, ?],
    state: ComposeState[SL, SR],
  ): (SL, SR) =
    (isStatelessScan(left), isStatelessScan(right)) match
      case (true, true)  => (nothingSentinel.asInstanceOf[SL], nothingSentinel.asInstanceOf[SR])
      case (true, false) => (nothingSentinel.asInstanceOf[SL], state.asInstanceOf[SR])
      case (false, true) => (state.asInstanceOf[SL], nothingSentinel.asInstanceOf[SR])
      case (false, false) =>
        val (sl, sr) = state.asInstanceOf[(SL, SR)]
        (sl, sr)

  private def combineState[SL, SR](
    left: Scan[?, ?],
    right: Scan[?, ?],
    sl: SL,
    sr: SR,
  ): ComposeState[SL, SR] =
    (isStatelessScan(left), isStatelessScan(right)) match
      case (true, true)   => nothingSentinel.asInstanceOf[ComposeState[SL, SR]]
      case (true, false)  => sr.asInstanceOf[ComposeState[SL, SR]]
      case (false, true)  => sl.asInstanceOf[ComposeState[SL, SR]]
      case (false, false) => (sl, sr).asInstanceOf[ComposeState[SL, SR]]

  private def splitFanoutState[SL, SR, OL, OR](
    left: Scan[?, ?],
    right: Scan[?, ?],
    state: FanoutState[SL, SR, OL, OR],
  ): (SL, SR, Chunk[OL], Chunk[OR]) =
    (isStatelessScan(left), isStatelessScan(right)) match
      case (true, true) =>
        val (bufL, bufR) = state.asInstanceOf[(Chunk[OL], Chunk[OR])]
        (nothingSentinel.asInstanceOf[SL], nothingSentinel.asInstanceOf[SR], bufL, bufR)
      case (true, false) =>
        val (sr, bufL, bufR) = state.asInstanceOf[(SR, Chunk[OL], Chunk[OR])]
        (nothingSentinel.asInstanceOf[SL], sr, bufL, bufR)
      case (false, true) =>
        val (sl, bufL, bufR) = state.asInstanceOf[(SL, Chunk[OL], Chunk[OR])]
        (sl, nothingSentinel.asInstanceOf[SR], bufL, bufR)
      case (false, false) =>
        val (sl, sr, bufL, bufR) = state.asInstanceOf[(SL, SR, Chunk[OL], Chunk[OR])]
        (sl, sr, bufL, bufR)

  private def combineFanoutState[SL, SR, OL, OR](
    left: Scan[?, ?],
    right: Scan[?, ?],
    sl: SL, sr: SR, bufL: Chunk[OL], bufR: Chunk[OR],
  ): FanoutState[SL, SR, OL, OR] =
    (isStatelessScan(left), isStatelessScan(right)) match
      case (true, true)   => (bufL, bufR).asInstanceOf[FanoutState[SL, SR, OL, OR]]
      case (true, false)  => (sr, bufL, bufR).asInstanceOf[FanoutState[SL, SR, OL, OR]]
      case (false, true)  => (sl, bufL, bufR).asInstanceOf[FanoutState[SL, SR, OL, OR]]
      case (false, false) => (sl, sr, bufL, bufR).asInstanceOf[FanoutState[SL, SR, OL, OR]]

  /** Fold a right scan over a chunk of intermediate values, threading state. */
  private def foldStep[M, O, SR, ER](
    right: Aux[M, O, SR, ER],
    sr: SR,
    intermediates: Chunk[M],
  ): (SR, Chunk[O]) < ER =
    intermediates.foldLeft[(SR, Chunk[O]) < ER](
      ((sr, Chunk.empty[O]): (SR, Chunk[O])).asInstanceOf[(SR, Chunk[O]) < ER]
    ) { (accEffect, m) =>
      accEffect.flatMap { case (sCur, accChunk) =>
        right.step(sCur, m).map { case (sNext, outs) =>
          (sNext, accChunk ++ outs)
        }
      }
    }

end ComposeOps
