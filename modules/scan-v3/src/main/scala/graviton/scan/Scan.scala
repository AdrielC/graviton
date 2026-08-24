package graviton.scan

import kyo.*

/** The core scan type.
 *
 *  A Scan[I, O] represents a stateful transducer that reads elements of type I
 *  and produces elements of type O. It is a GADT: the structure of a composed
 *  scan is introspectable at runtime, and the compiler knows the type members
 *  [[S]] (state) and [[E]] (effect row) statically so that composition can fuse
 *  pure scans, unify effect rows, and erase stateless composition.
 *
 *  ## Design decisions
 *
 *  Why `S` as a type member rather than a third type parameter?
 *  Because you rarely want to carry state around in user-facing type signatures.
 *  `Scan[Byte, Digest]` reads cleanly; `Scan[Byte, Digest, HasherState, Mem]` is
 *  noise. The state type is recoverable via the `Aux` pattern when composition
 *  needs to inspect it, and erased when it doesn't.
 *
 *  Why `S = Nothing` for stateless, not `Unit` or a sentinel?
 *  Because Nothing is the zero of the type lattice. Any function `Nothing => X`
 *  is vacuously defined. Composition can pattern-match on `S <:< Nothing` via a
 *  match type and the Nothing branch erases the state threading entirely at
 *  the type level. Unit would force a runtime ignore of `()`, which is wasteful
 *  and makes stateless composition a special case rather than a structural
 *  property.
 *
 *  Why `E` as a Kyo effect row?
 *  Because Kyo already encodes effect rows as intersection types in the `-S`
 *  position of `A < S`. A pure scan has `E = Any` (empty row). A scan that
 *  allocates a hasher has `E <: Mem`. Composition takes the intersection of
 *  effect rows at the type level, which is exactly what we want: composing
 *  pure with pure stays pure; composing pure with `Mem` gives `Mem`; composing
 *  `Mem` with `Abort[IOException]` gives `Mem & Abort[IOException]`.
 *
 *  Why capture checking (`^`) on resource-holding scans?
 *  Because effect rows track *what kinds of effects* a scan performs, but they
 *  don't track *which specific resources* a scan holds. A scan that opens a
 *  file handle needs to guarantee that the handle is closed even if composition
 *  drops it. Capture checking lets us annotate the scan's type with the
 *  specific capabilities it captures, and the compiler enforces that those
 *  capabilities don't leak out of the scope that created them.
 */
sealed abstract class Scan[-I, +O]:
  /** The state type of this scan.
   *
   *  `Nothing` means stateless: the scan is a pure function or a fold over
   *  external state. For stateless scans, [[ComposeState]] erases state
   *  threading during composition so that a chain of pure scans fuses to a
   *  single function with no tupled state to carry around.
   */
  type S

  /** The Kyo effect row of this scan.
   *
   *  `Any` means pure: no effects pending. A stateful scan that allocates an
   *  in-memory hasher has `E <: Mem`. A scan that reads from a file handle has
   *  `E <: Sync` (or `Abort[IOException] & Sync` for checked exceptions). The
   *  row composes via intersection: `E1 & E2` after [[>>>]].
   */
  type E

  /** Initialize the scan's state.
   *
   *  For stateless scans (`S = Nothing`), this returns a value of type
   *  `Nothing < Any`, which the compiler knows is uninhabited. Composition
   *  arranges the caller to never demand this value for stateless scans.
   *
   *  For stateful scans, the initial state is produced within the effect row
   *  `E`. This is where resource allocation happens: a hasher scan's `init`
   *  returns a `HasherState < Mem` that includes the allocated buffers, and
   *  the corresponding cleanup is registered in the caller's scope via
   *  [[release]].
   */
  def init: S < E

  /** Consume one input element, producing zero or more outputs and a new
   *  state.
   *
   *  The result is a tuple in the effect row `E`. Note that `step` is allowed
   *  to produce zero outputs on a given input — this is how buffering scans
   *  (like CDC chunkers) work.
   */
  def step(state: S, input: I): (S, Chunk[O]) < E

  /** Flush any buffered state at end of input, producing final outputs.
   *
   *  For scans that don't buffer (pure maps, folds), this is empty. For
   *  chunkers, this emits the last partial chunk. For hashers, this finalizes
   *  the digest.
   */
  def flush(state: S): Chunk[O] < E

  /** Release any resources held in the state.
   *
   *  This is called LIFO with [[flush]] during scan teardown. For stateless
   *  and pure-stateful scans this is a no-op. For scans that hold resources
   *  (file handles, mutable buffers, external connections), this is where
   *  cleanup happens.
   *
   *  Capture checking ensures that any resource captured in the state must
   *  either be released here or be a shared capability that doesn't require
   *  exclusive cleanup.
   */
  def release(state: S): Unit < E

end Scan

object Scan:

  /** Aux pattern for exposing the state type when composition or interpretation
   *  needs it.
   *
   *  ```
   *  val s: Scan.Aux[Byte, Digest, HasherState, Mem] = ???
   *  ```
   */
  type Aux[-I, +O, S0, E0] = Scan[I, O] { type S = S0; type E = E0 }

  /** Synonym for stateless scans. */
  type Stateless[-I, +O, E0] = Scan[I, O] { type S = Nothing; type E = E0 }

  /** Synonym for pure scans (stateless and no effects). */
  type Pure[-I, +O] = Scan[I, O] { type S = Nothing; type E = Any }

  // -- Constructors ----------------------------------------------------------

  /** Lift a pure function to a stateless, pure scan.
   *
   *  This is the most-fused form: `arr(f) >>> arr(g)` reduces to `arr(g ∘ f)`
   *  at composition time, not at interpretation time, because composition
   *  recognizes the `Arr` node and fuses the underlying functions.
   */
  def arr[I, O](f: I => O): Pure[I, O] = Arr(f)

  /** The identity scan. A special case of `arr` that composition can erase
   *  entirely: `id >>> scan == scan` and `scan >>> id == scan`.
   */
  def id[A]: Pure[A, A] = Id.asInstanceOf[Pure[A, A]]

  /** Stateful fold with no effects. State is modelled as an arbitrary type S,
   *  typically a domain case class. The fold function is pure.
   */
  def fold[I, O, S0](seed: S0)(f: (S0, I) => (S0, Chunk[O]))(flushFn: S0 => Chunk[O] = (_: S0) => Chunk.empty[O]): Aux[I, O, S0, Any] =
    Fold(seed, f, flushFn)

  /** Stateful scan with effects. The init, step, flush, and release functions
   *  all live in the same effect row E. Use this for scans that allocate
   *  resources: hashers, encoders, buffers.
   */
  def effectful[I, O, S0, E0](
    init0: S0 < E0,
    step0: (S0, I) => (S0, Chunk[O]) < E0,
    flush0: S0 => Chunk[O] < E0,
    release0: S0 => Unit < E0 = (_: S0) => (()),
  ): Aux[I, O, S0, E0] =
    Effectful(init0, step0, flush0, release0)

  // -- GADT nodes ------------------------------------------------------------
  //
  // These are the introspectable structure of a composed scan. An interpreter
  // (such as the Kyo-based runner in scan.interp.KyoInterp) pattern-matches on
  // these nodes to produce an executable representation. This is the "free"
  // part of the design: composition builds structure, interpretation runs it.

  /** The identity scan, as a GADT node. */
  private[scan] case object Id extends Scan[Any, Any]:
    type S = Nothing
    type E = Any
    def init: Nothing < Any = throw new UnsupportedOperationException("Id.init should never be called")
    def step(state: Nothing, input: Any): (Nothing, Chunk[Any]) < Any = (state, Chunk.empty)
    def flush(state: Nothing): Chunk[Any] < Any = Chunk.empty
    def release(state: Nothing): Unit < Any = ()

  /** A pure function lifted into a scan. Composition fuses adjacent `Arr` nodes. */
  private[scan] final case class Arr[I, O](f: I => O) extends Scan[I, O]:
    type S = Nothing
    type E = Any
    def init: Nothing < Any = throw new UnsupportedOperationException("Arr.init should never be called")
    def step(state: Nothing, input: I): (Nothing, Chunk[O]) < Any = (state, Chunk(f(input)))
    def flush(state: Nothing): Chunk[O] < Any = Chunk.empty
    def release(state: Nothing): Unit < Any = ()

  /** A pure stateful fold. */
  private[scan] final case class Fold[I, O, S0](
    seed: S0,
    f: (S0, I) => (S0, Chunk[O]),
    flushFn: S0 => Chunk[O],
  ) extends Scan[I, O]:
    type S = S0
    type E = Any
    def init: S0 < Any = seed
    def step(state: S0, input: I): (S0, Chunk[O]) < Any = f(state, input)
    def flush(state: S0): Chunk[O] < Any = flushFn(state)
    def release(state: S0): Unit < Any = ()

  /** An effectful scan with explicit init/step/flush/release. This is the
   *  escape hatch for anything that needs the full generality.
   */
  private[scan] final case class Effectful[I, O, S0, E0](
    init0: S0 < E0,
    step0: (S0, I) => (S0, Chunk[O]) < E0,
    flush0: S0 => Chunk[O] < E0,
    release0: S0 => Unit < E0,
  ) extends Scan[I, O]:
    type S = S0
    type E = E0
    def init: S0 < E0 = init0
    def step(state: S0, input: I): (S0, Chunk[O]) < E0 = step0(state, input)
    def flush(state: S0): Chunk[O] < E0 = flush0(state)
    def release(state: S0): Unit < E0 = release0(state)

  /** Sequential composition: outputs of `left` feed inputs of `right`.
   *
   *  The composed state is [[ComposeState]] of the two state types, which
   *  reduces to just one side's state when the other is Nothing. The composed
   *  effect row is the intersection of the two rows.
   */
  private[scan] final case class AndThen[I, M, O, SL, SR, EL, ER](
    left: Aux[I, M, SL, EL],
    right: Aux[M, O, SR, ER],
  ) extends Scan[I, O]:
    type S = ComposeState[SL, SR]
    type E = EL & ER
    // init/step/flush/release for AndThen are defined in terms of left and
    // right's operations, but the precise signature depends on match-type
    // reduction of ComposeState. The interpreter in KyoInterp handles this
    // by pattern-matching on the node rather than calling these methods
    // directly, which is why they're implemented by punting to the
    // interpreter. (See the detailed interpreter file for how composition
    // is actually executed.)
    def init: ComposeState[SL, SR] < (EL & ER) = ComposeOps.initAndThen(left, right)
    def step(state: ComposeState[SL, SR], input: I): (ComposeState[SL, SR], Chunk[O]) < (EL & ER) =
      ComposeOps.stepAndThen(left, right, state, input)
    def flush(state: ComposeState[SL, SR]): Chunk[O] < (EL & ER) =
      ComposeOps.flushAndThen(left, right, state)
    def release(state: ComposeState[SL, SR]): Unit < (EL & ER) =
      ComposeOps.releaseAndThen[SL, SR, EL, ER](left, right, state)

  /** Fanout (broadcast): send each input to both scans, pair their outputs.
   *
   *  Output chunks are paired positionally: if `left` produces outputs
   *  `[o1, o2]` and `right` produces `[r1, r2]` for the same input, the
   *  fanout emits `[(o1, r1), (o2, r2)]`. If the chunks have different
   *  lengths, the result is the elementwise zip truncated to the shorter
   *  chunk, with the remainder buffered in state for the next step. This is
   *  the semantically least-surprising behaviour for arrow fanout.
   */
  private[scan] final case class Fanout[I, OL, OR, SL, SR, EL, ER](
    left: Aux[I, OL, SL, EL],
    right: Aux[I, OR, SR, ER],
  ) extends Scan[I, (OL, OR)]:
    type S = FanoutState[SL, SR, OL, OR]
    type E = EL & ER
    def init: FanoutState[SL, SR, OL, OR] < (EL & ER) = ComposeOps.initFanout(left, right)
    def step(state: FanoutState[SL, SR, OL, OR], input: I): (FanoutState[SL, SR, OL, OR], Chunk[(OL, OR)]) < (EL & ER) =
      ComposeOps.stepFanout(left, right, state, input)
    def flush(state: FanoutState[SL, SR, OL, OR]): Chunk[(OL, OR)] < (EL & ER) =
      ComposeOps.flushFanout(left, right, state)
    def release(state: FanoutState[SL, SR, OL, OR]): Unit < (EL & ER) =
      ComposeOps.releaseFanout(left, right, state)

  /** Product (parallel on tuples): `left` runs on the first component of each
   *  input, `right` on the second. Distinct from Fanout — here the inputs are
   *  independent, not shared.
   */
  private[scan] final case class Product[IL, IR, OL, OR, SL, SR, EL, ER](
    left: Aux[IL, OL, SL, EL],
    right: Aux[IR, OR, SR, ER],
  ) extends Scan[(IL, IR), (OL, OR)]:
    type S = ComposeState[SL, SR]
    type E = EL & ER
    def init: ComposeState[SL, SR] < (EL & ER) = ComposeOps.initBoth[SL, SR, EL, ER](left, right)
    def step(state: ComposeState[SL, SR], input: (IL, IR)): (ComposeState[SL, SR], Chunk[(OL, OR)]) < (EL & ER) =
      ComposeOps.stepProduct(left, right, state, input)
    def flush(state: ComposeState[SL, SR]): Chunk[(OL, OR)] < (EL & ER) =
      ComposeOps.flushProduct(left, right, state)
    def release(state: ComposeState[SL, SR]): Unit < (EL & ER) =
      ComposeOps.releaseAndThen[SL, SR, EL, ER](left, right, state)

  /** Choice (sum): `left` runs on `Left` inputs, `right` on `Right`. The
   *  output type is the union of the two result types — in Scala 3 we can
   *  use a true union type `OL | OR` here rather than `Either[OL, OR]`,
   *  which is more useful when both sides share a result shape.
   */
  private[scan] final case class Choice[IL, IR, OL, OR, SL, SR, EL, ER](
    left: Aux[IL, OL, SL, EL],
    right: Aux[IR, OR, SR, ER],
  ) extends Scan[Either[IL, IR], OL | OR]:
    type S = ComposeState[SL, SR]
    type E = EL & ER
    def init: ComposeState[SL, SR] < (EL & ER) = ComposeOps.initBoth[SL, SR, EL, ER](left, right)
    def step(state: ComposeState[SL, SR], input: Either[IL, IR]): (ComposeState[SL, SR], Chunk[OL | OR]) < (EL & ER) =
      ComposeOps.stepChoice(left, right, state, input)
    def flush(state: ComposeState[SL, SR]): Chunk[OL | OR] < (EL & ER) =
      ComposeOps.flushChoice(left, right, state)
    def release(state: ComposeState[SL, SR]): Unit < (EL & ER) =
      ComposeOps.releaseAndThen[SL, SR, EL, ER](left, right, state)

  /** A chained sequence of pure functions, kept as a separate node so that
   *  fusion can avoid building a linked list of `AndThen(Arr, Arr)` nodes
   *  that recursively call each other and blow the stack on deep pipelines.
   *
   *  The invariant is: `FusedArr(fs)` is equivalent to `fs.reduce(_ andThen _)`
   *  but represented as a Chunk so that we can spill to an `IArray[Any => Any]`
   *  once the chain exceeds a threshold (see `Compose.FusionThreshold`).
   *  Composition with another `FusedArr` appends the chunks. Composition
   *  with a non-Arr scan splits back into an AndThen.
   */
  private[scan] final case class FusedArr[I, O](fs: IArray[Any => Any]) extends Scan[I, O]:
    type S = Nothing
    type E = Any
    def init: Nothing < Any = throw new UnsupportedOperationException("FusedArr.init should never be called")
    def step(state: Nothing, input: I): (Nothing, Chunk[O]) < Any =
      var acc: Any = input
      var i        = 0
      while i < fs.length do
        acc = fs(i)(acc)
        i += 1
      (state, Chunk(acc.asInstanceOf[O]))
    def flush(state: Nothing): Chunk[O] < Any = Chunk.empty
    def release(state: Nothing): Unit < Any = ()

end Scan
