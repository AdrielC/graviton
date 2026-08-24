package graviton.scan.interp

import kyo.*
import zio.{Chunk as ZChunk, ZIO, Task}
import zio.stream.ZPipeline
import graviton.scan.*
import graviton.scan.Scan.*

/** Run a composed Scan against inputs.
 *
 *  Two flavors:
 *
 *  1. Direct execution within Kyo: take an `Iterable[I]` (or Kyo stream),
 *     produce the final outputs plus summary.
 *
 *  2. Compile to ZIO: take the Scan, produce a `ZPipeline[Any, Throwable, I, O]`
 *     that integrates with the rest of Graviton's streaming stack.
 *
 *  The ZIO compilation path is the one that matters for production use.
 *  Kyo is used internally for effect-row tracking, but the public surface
 *  into Graviton's existing ZIO-based infrastructure is ZPipeline, because
 *  that's what `CasBlobStore.put` and friends consume.
 *
 *  ## Fusion verification
 *
 *  One important property: a fully-pure composed scan (all Arr / FusedArr,
 *  no effectful stages) should compile to a single `ZPipeline.map` with
 *  the fused function. The interpreter checks for this and takes the fast
 *  path, rather than threading state through Kyo's effect machinery for
 *  what is ultimately a pure transformation.
 */
object KyoInterp:

  /** Run a scan over an iterable of inputs, collecting outputs and the final
   *  state into a summary. Runs within Kyo's effect row E plus a Scope
   *  for LIFO cleanup of whatever the scan allocated.
   */
  def run[I, O, S0, E0](
    scan: Aux[I, O, S0, E0],
    inputs: Iterable[I],
  ): (S0, ZChunk[O]) < E0 =
    // Note: this in-process runner does not register `release` with a Scope —
    // it just chains it after flush. That's correct here because we own the
    // state's lifetime within this function. The ZPipeline path below uses
    // ZIO's Scope for proper release-on-stream-termination semantics.
    val out: (S0, ZChunk[O]) < E0 =
      scan.init.flatMap { initialState =>
        val foldedEffect: (S0, ZChunk[O]) < E0 =
          inputs.foldLeft[(S0, ZChunk[O]) < E0](
            ((initialState, ZChunk.empty[O]): (S0, ZChunk[O])).asInstanceOf[(S0, ZChunk[O]) < E0]
          ) { (accEff, i) =>
            accEff.flatMap { case (s, outs) =>
              scan.step(s, i).map { case (s2, newOuts) =>
                (s2, outs ++ ZChunk.fromIterable(newOuts))
              }
            }
          }
        foldedEffect.flatMap { case (finalState, accOuts) =>
          scan.flush(finalState).flatMap { flushOuts =>
            scan.release(finalState).map { _ =>
              (finalState, accOuts ++ ZChunk.fromIterable(flushOuts))
            }
          }
        }
      }
    out

  /** Compile a scan to a ZPipeline for use in the existing Graviton streaming
   *  infrastructure.
   *
   *  Fast path: if the scan is statically known to be pure (structurally
   *  a chain of Arrs), emits a `ZPipeline.map` with the fused function.
   *
   *  Slow path: uses `ZPipeline.mapAccumZIO` to thread state, translating
   *  Kyo effects into ZIO effects at each step. This is the general case
   *  that works for any composition.
   *
   *  The translation from Kyo `A < E` to ZIO `Task[A]` uses Kyo's `ZIOs.run`
   *  which handles `Sync` and `Abort[Throwable]` effect rows. Other rows
   *  (e.g. `Var`, `Emit`) need an explicit handler before reaching ZIO land.
   */
  def toZPipeline[I, O, S0, E0](scan: Aux[I, O, S0, E0]): ZPipeline[Any, Throwable, I, O] =
    scan match
      // Fast path: pure map.
      case Id               => ZPipeline.identity[I].asInstanceOf[ZPipeline[Any, Throwable, I, O]]
      case Arr(f)           => ZPipeline.map[I, O](x => f.asInstanceOf[I => O](x))
      case FusedArr(fs)     =>
        ZPipeline.map[I, O] { x =>
          var acc: Any = x
          var i        = 0
          while i < fs.length do
            acc = fs(i)(acc)
            i += 1
          acc.asInstanceOf[O]
        }

      // General path: mapChunksZIO with state threading.
      // We wrap the whole pipeline in a scope so that `release` runs on
      // stream termination.
      case other =>
        ZPipeline.unwrapScoped[Any] {
          for
            stateRef <- zio.Ref.Synchronized.make[Option[S0]](None)
            _        <- kyoToTask(other.init).flatMap(s => stateRef.set(Some(s)))
            _        <- ZIO.addFinalizer(
                          stateRef.get.flatMap {
                            case Some(s) => kyoToTask(other.release(s)).orDie
                            case None    => ZIO.unit
                          }
                        )
            stepPipe = ZPipeline.mapChunksZIO[Any, Throwable, I, O] { inputChunk =>
                         stateRef.modifyZIO[Any, Throwable, ZChunk[O]] { maybeState =>
                           val state = maybeState.getOrElse(
                             throw new IllegalStateException("scan state not initialized")
                           )
                           foldChunk(other, state, inputChunk).map { case (s2, outs) =>
                             (outs, Some(s2))
                           }
                         }
                       }
          yield stepPipe
        }

  /** Internal helper: fold a scan over one input chunk, threading state. */
  private def foldChunk[I, O, S0, E0](
    scan: Aux[I, O, S0, E0],
    initialState: S0,
    chunk: ZChunk[I],
  ): Task[(S0, ZChunk[O])] =
    chunk.foldLeft[Task[(S0, ZChunk[O])]](ZIO.succeed((initialState, ZChunk.empty[O]))) {
      (acc, i) =>
        acc.flatMap { case (s, outs) =>
          kyoToTask(scan.step(s, i)).map { case (s2, newOuts) =>
            (s2, outs ++ ZChunk.fromIterable(newOuts))
          }
        }
    }

  /** Bridge: translate a Kyo computation to a ZIO Task.
   *
   *  Uses Kyo's official `ZIOs.run` integration which supports `Sync` and
   *  `Abort[Throwable]` effect rows. Other rows (e.g. `Var`, `Emit`) must
   *  be handled before calling this bridge.
   *
   *  Note: this is intentionally narrow — the cast `effect.asInstanceOf` is
   *  only safe when E0 is one of the rows ZIOs.run accepts. Callers that
   *  compose scans with richer Kyo effects need to handle them upstream.
   */
  private def kyoToTask[A, E0](effect: A < E0): Task[A] =
    ZIOs.run(effect.asInstanceOf[A < (Sync & Abort[Throwable])])

end KyoInterp
