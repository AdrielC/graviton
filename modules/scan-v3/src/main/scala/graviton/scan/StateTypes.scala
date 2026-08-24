package graviton.scan

import kyo.Chunk

/** Match types that compute the state type of a composed scan.
 *
 *  ## Why a marker type instead of `Nothing`
 *
 *  An earlier sketch of this design used `Nothing` as the "no state" marker
 *  and had `ComposeState[Nothing, S] = S` etc. as match cases. This does
 *  not actually reduce in Scala 3 (3.7 or 3.8): `Nothing` is the bottom
 *  type and the match-type machinery refuses to commit to a single case
 *  when the scrutinee is `Nothing`, because `Nothing <: T` for every `T`
 *  so multiple cases could conceivably apply.
 *
 *  The fix is to use a distinct opaque marker `StateNone`. Opaque types
 *  are sealed at the use site, so the compiler can reduce the match type
 *  exactly. Stateless scans declare their `S = StateNone` and composition
 *  collapses correctly.
 */
object StateTypes:

  /** The marker type for "no state".
   *
   *  An opaque alias of a singleton class so that:
   *
   *  - The type is concrete (the match-type reducer commits to it).
   *  - There is a single phantom value (`StateNone.value`) that scans with
   *    `S = StateNone` use during composition. The runtime never inspects
   *    it; it just threads it through.
   */
  opaque type StateNone <: Any = StateNone.Marker

  object StateNone:
    /** Hidden marker class. Not exposed outside this object. */
    final class Marker private[StateNone] ()
    val value: StateNone = new Marker

  /** The state type of a composed scan.
   *
   *  {{{
   *  ComposeState[StateNone, S]         = S
   *  ComposeState[S, StateNone]         = S
   *  ComposeState[StateNone, StateNone] = StateNone
   *  ComposeState[SL, SR]               = (SL, SR)   // neither is StateNone
   *  }}}
   */
  type ComposeState[SL, SR] = SL match
    case StateNone => SR
    case _         => SR match
      case StateNone => SL
      case _         => (SL, SR)

  /** The state type of a Fanout. Fanout has to buffer unmatched outputs
   *  from either side when the two scans produce chunks of different
   *  lengths, so the state always carries two output buffers in addition
   *  to whatever side state survives the `StateNone`-collapses.
   */
  type FanoutState[SL, SR, OL, OR] = SL match
    case StateNone => SR match
      case StateNone => (Chunk[OL], Chunk[OR])
      case _         => (SR, Chunk[OL], Chunk[OR])
    case _         => SR match
      case StateNone => (SL, Chunk[OL], Chunk[OR])
      case _         => (SL, SR, Chunk[OL], Chunk[OR])

  /** Helper: is this type exactly `StateNone`?
   *
   *  Used by composition ops to decide whether to initialize state at all.
   */
  type IsStateNone[S] <: Boolean = S match
    case StateNone => true
    case _         => false

end StateTypes

// Re-export so that Scan.scala doesn't need to qualify everything.
export StateTypes.{StateNone, ComposeState, FanoutState, IsStateNone}
