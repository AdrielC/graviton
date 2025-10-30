package graviton.streams.scan

import graviton.core.scan.*
import zio.*

/**
 * ZIO-based mutable state interpreter using Refs.
 *
 * This interpreter provides high-performance mutable state for hot paths:
 * - Repr[S] is a bundle of zio.Ref cells
 * - Updates are in-place (via Ref.set/update)
 * - Zero allocation for state updates in the hot loop
 *
 * Usage:
 * {{{
 *   val spec: FIM[MyState] = counter("bytes") ⊗ flag("done")
 *   val interpreter = new RefInterpreter()
 *
 *   for {
 *     refs <- interpreter.allocZ(spec)
 *     _    <- interpreter.writeZ(spec, refs, newState)
 *     curr <- interpreter.readZ(spec, refs)
 *   } yield curr
 * }}}
 */
class RefInterpreter extends StateInterpreter:

  /**
   * Representation is a map from labels to Refs.
   * We use Any to support heterogeneous state components.
   */
  type Repr[S] = Map[String, Ref[Any]]

  def alloc[S](spec: FIM[S]): () => Repr[S] =
    () =>
      throw new UnsupportedOperationException(
        "Use allocZ for ZIO-based allocation"
      )

  def read[S](spec: FIM[S], repr: Repr[S]): S =
    throw new UnsupportedOperationException(
      "Use readZ for ZIO-based reading"
    )

  def write[S](spec: FIM[S], repr: Repr[S], value: S): Repr[S] =
    throw new UnsupportedOperationException(
      "Use writeZ for ZIO-based writing"
    )

  /**
   * Allocate Refs for all state components (ZIO effect).
   */
  def allocZ[S](spec: FIM[S]): UIO[Repr[S]] =
    initializeRefs(spec)

  /**
   * Read current state snapshot (ZIO effect).
   */
  def readZ[S](spec: FIM[S], refs: Repr[S]): UIO[S] =
    extractFromRefs(spec, refs)

  /**
   * Write/update state (ZIO effect).
   */
  def writeZ[S](spec: FIM[S], refs: Repr[S], value: S): UIO[Unit] =
    updateRefs(spec, refs, value)

  /**
   * Update state with a function (atomic for single components).
   */
  def modifyZ[S](spec: FIM[S], refs: Repr[S])(f: S => S): UIO[S] =
    for
      current <- readZ(spec, refs)
      updated  = f(current)
      _       <- writeZ(spec, refs, updated)
    yield updated

  /**
   * Initialize all Refs from FIM spec.
   */
  private def initializeRefs[S](spec: FIM[S]): UIO[Repr[S]] =
    spec match
      case FIM.UnitF() =>
        ZIO.succeed(Map.empty)

      case FIM.Labeled(label, init) =>
        Ref.make[Any](init()).map(ref => Map(label -> ref))

      case FIM.Prod(left, right) =>
        for
          lRefs <- initializeRefs(left)
          rRefs <- initializeRefs(right)
        yield lRefs ++ rRefs

      case FIM.IsoF(base, _) =>
        // For mutable refs, we initialize the base representation
        initializeRefs(base)

  /**
   * Extract current values from all Refs.
   */
  private def extractFromRefs[S](spec: FIM[S], refs: Repr[S]): UIO[S] =
    spec match
      case FIM.UnitF() =>
        ZIO.succeed(().asInstanceOf[S])

      case FIM.Labeled(label, _) =>
        refs.get(label) match
          case Some(ref) => ref.get.map(_.asInstanceOf[S])
          case None      => ZIO.die(new IllegalStateException(s"Missing ref for label: $label"))

      case FIM.Prod(left, right) =>
        for
          l <- extractFromRefs(left, refs)
          r <- extractFromRefs(right, refs)
        yield (l, r).asInstanceOf[S]

      case FIM.IsoF(base, iso) =>
        extractFromRefs(base, refs).map(baseVal => iso.to(baseVal))

  /**
   * Update all Refs with new values.
   */
  private def updateRefs[S](spec: FIM[S], refs: Repr[S], value: S): UIO[Unit] =
    spec match
      case FIM.UnitF() =>
        ZIO.unit

      case FIM.Labeled(label, _) =>
        refs.get(label) match
          case Some(ref) => ref.set(value)
          case None      => ZIO.die(new IllegalStateException(s"Missing ref for label: $label"))

      case FIM.Prod(left, right) =>
        val (lVal, rVal) = value.asInstanceOf[(Any, Any)]
        for
          _ <- updateRefs[Any](left.asInstanceOf[FIM[Any]], refs, lVal)
          _ <- updateRefs[Any](right.asInstanceOf[FIM[Any]], refs, rVal)
        yield ()

      case FIM.IsoF(base, iso) =>
        val baseVal = iso.from(value)
        updateRefs(base, refs, baseVal)

/**
 * Optimized RefInterpreter that uses a single Ref[Rec] for the entire state.
 *
 * This can be more efficient than Map[String, Ref[Any]] when:
 * - State updates are frequent
 * - Full state snapshot is rarely needed
 * - You want atomic updates across multiple fields
 */
class SingleRefInterpreter extends StateInterpreter:

  type Repr[S] = Ref[Rec]

  def alloc[S](spec: FIM[S]): () => Repr[S] =
    () => throw new UnsupportedOperationException("Use allocZ for ZIO-based allocation")

  def read[S](spec: FIM[S], repr: Repr[S]): S =
    throw new UnsupportedOperationException("Use readZ for ZIO-based reading")

  def write[S](spec: FIM[S], repr: Repr[S], value: S): Repr[S] =
    throw new UnsupportedOperationException("Use writeZ for ZIO-based writing")

  /**
   * Allocate a single Ref holding the entire Rec state.
   */
  def allocZ[S](spec: FIM[S]): UIO[Repr[S]] =
    val initialRec = RecInterpreter.alloc(spec)()
    Ref.make(initialRec)

  /**
   * Read current state from the Ref.
   */
  def readZ[S](spec: FIM[S], ref: Repr[S]): UIO[S] =
    ref.get.map(rec => RecInterpreter.read(spec, rec))

  /**
   * Update state in the Ref.
   */
  def writeZ[S](spec: FIM[S], ref: Repr[S], value: S): UIO[Unit] =
    ref.update(rec => RecInterpreter.write(spec, rec, value))

  /**
   * Atomic modify with function.
   */
  def modifyZ[S](spec: FIM[S], ref: Repr[S])(f: S => S): UIO[S] =
    ref.modify { rec =>
      val current = RecInterpreter.read(spec, rec)
      val updated = f(current)
      val newRec  = RecInterpreter.write(spec, rec, updated)
      (updated, newRec)
    }

/**
 * Factory for selecting the best interpreter based on context.
 */
object StateInterpreterZIO:

  /**
   * Choose interpreter based on FIM structure and usage pattern.
   *
   * Heuristics:
   * - Stateless (Unit): use ImmutableInterpreter (zero overhead)
   * - Few components, frequent updates: RefInterpreter
   * - Many components, atomic updates needed: SingleRefInterpreter
   * - Compile-time known structure: ImmutableInterpreter or RecInterpreter
   */
  def selectInterpreter[S](
    spec: FIM[S],
    hotPath: Boolean = true,
    atomicUpdates: Boolean = false,
  ): StateInterpreter =
    if CompileTimeOpt.isStateless(spec) then ImmutableInterpreter
    else if atomicUpdates then new SingleRefInterpreter
    else if hotPath then new RefInterpreter
    else RecInterpreter

/**
 * Extension methods for working with FIM and ZIO interpreters.
 */
extension [S](spec: FIM[S])

  /**
   * Allocate mutable state using RefInterpreter.
   */
  def allocRefs: UIO[Map[String, Ref[Any]]] =
    new RefInterpreter().allocZ(spec)

  /**
   * Allocate immutable state.
   */
  def allocImmutable: S =
    ImmutableInterpreter.alloc(spec)()

  /**
   * Allocate as named tuple (Rec).
   */
  def allocRec: Rec =
    RecInterpreter.alloc(spec)()
