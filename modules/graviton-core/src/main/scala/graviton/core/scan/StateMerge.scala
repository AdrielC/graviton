package graviton.core.scan

import kyo.Record

import scala.util.NotGiven

/**
 * Typeclass for composing scan states during scan composition.
 *
 * Uses the Aux pattern: `Out` is a dependent type member so that composed
 * state types are inferred, not manually threaded.
 *
 * The composed state is **user-facing** — it's the summary type that callers
 * get back from `runChunk`, `toSink`, etc. So the merge strategy directly
 * affects the API surface: Record fields are accessible by name, and tuple
 * fields by position.
 *
 * Instance resolution priority:
 *   1. `Unit + Unit = Unit`
 *   2. `Unit + S = S`  (left identity)
 *   3. `S + Unit = S`  (right identity)
 *   4. `Record[A] + Record[B] = Record[A & B]`  (record field union)
 *   5. `(S1, S2)` fallback (product)
 *
 * Laws (up to isomorphism):
 *   - Left identity:  `merge((), s) ≅ s`
 *   - Right identity: `merge(s, ()) ≅ s`
 *   - Projection:     `left(merge(a, b)) == a`
 *   - Projection:     `right(merge(a, b)) == b`
 */
trait StateMerge[S1, S2]:
  type Out
  def merge(s1: S1, s2: S2): Out
  def left(out: Out): S1
  def right(out: Out): S2

object StateMerge extends StateMergeLowPriority:

  /** Aux pattern: expose `Out` as a type parameter for callers that need it. */
  type Aux[S1, S2, O] = StateMerge[S1, S2] { type Out = O }

  /** Summon the merge instance and expose the dependent `Out` type. */
  inline def apply[S1, S2](using sm: StateMerge[S1, S2]): sm.type = sm

  // --- Priority 1: Unit + Unit = Unit ---

  given unitUnit: Aux[Unit, Unit, Unit] = new StateMerge[Unit, Unit]:
    type Out = Unit
    def merge(s1: Unit, s2: Unit): Unit = ()
    def left(out: Unit): Unit           = ()
    def right(out: Unit): Unit          = ()

  // --- Priority 2: Unit + S = S (left identity) ---

  given leftUnit[S](using NotGiven[S =:= Unit]): Aux[Unit, S, S] = new StateMerge[Unit, S]:
    type Out = S
    def merge(s1: Unit, s2: S): S = s2
    def left(out: S): Unit        = ()
    def right(out: S): S          = out

  // --- Priority 3: S + Unit = S (right identity) ---

  given rightUnit[S](using NotGiven[S =:= Unit]): Aux[S, Unit, S] = new StateMerge[S, Unit]:
    type Out = S
    def merge(s1: S, s2: Unit): S = s1
    def left(out: S): S           = out
    def right(out: S): Unit       = ()

  // --- Priority 4: Record[A] + Record[B] = Record[A & B] (field union) ---
  //
  // kyo.Record `&` merges fields at the JVM level (Map union).
  // After merge, both sides' fields are accessible by name.
  //
  // INVARIANT: Field names must not overlap. Overlap silently takes right-side value.

  given recordUnion[A, B]: Aux[Record[A], Record[B], Record[A & B]] = new StateMerge[Record[A], Record[B]]:
    type Out = Record[A & B]

    def merge(s1: Record[A], s2: Record[B]): Record[A & B] =
      s1.asInstanceOf[Record[Any]].&(s2.asInstanceOf[Record[Any]]).asInstanceOf[Record[A & B]]

    def left(out: Record[A & B]): Record[A] =
      out.asInstanceOf[Record[A]]

    def right(out: Record[A & B]): Record[B] =
      out.asInstanceOf[Record[B]]

end StateMerge

// --- Priority 5 (low): fallback to product ---

trait StateMergeLowPriority:

  given pair[S1, S2]: StateMerge.Aux[S1, S2, (S1, S2)] = new StateMerge[S1, S2]:
    type Out = (S1, S2)
    def merge(s1: S1, s2: S2): (S1, S2) = (s1, s2)
    def left(out: (S1, S2)): S1         = out._1
    def right(out: (S1, S2)): S2        = out._2
