package graviton.core.scan

/**
 * Interpreters for FIM state descriptions.
 *
 * Convert abstract FIM[S] descriptions into concrete runtime representations:
 * - ImmutableInterpreter: Uses tuples/case classes (structural immutability)
 * - RecInterpreter: Uses named tuples (Rec type from existing FreeScan)
 *
 * Each interpreter provides:
 * - alloc: Create initial state representation
 * - read: Extract pure value from representation
 * - write: Update representation with new value
 *
 * For ZIO-based mutable interpretation, see StateInterpreterZIO.
 */
trait StateInterpreter:
  /** The concrete representation family for state type S */
  type Repr[S]

  /**
   * Allocate initial representation from FIM description.
   * Returns a thunk to support lazy initialization.
   */
  def alloc[S](spec: FIM[S]): () => Repr[S]

  /**
   * Read pure state value from representation.
   * For immutable interpreters, this may be identity.
   * For mutable (Ref-based), this performs a snapshot.
   */
  def read[S](spec: FIM[S], repr: Repr[S]): S

  /**
   * Write/update state representation with new value.
   * For immutable: returns new representation.
   * For mutable: performs side effect and returns unit or same repr.
   */
  def write[S](spec: FIM[S], repr: Repr[S], value: S): Repr[S]

/**
 * Immutable structural interpreter using tuples.
 *
 * Repr[S] = S (the representation IS the value).
 * This is zero-overhead for pure functional scans.
 */
object ImmutableInterpreter extends StateInterpreter:

  type Repr[S] = S

  def alloc[S](spec: FIM[S]): () => S =
    () => initialize(spec)

  def read[S](spec: FIM[S], repr: S): S =
    repr

  def write[S](spec: FIM[S], repr: S, value: S): S =
    value

  /**
   * Initialize state value from FIM description.
   */
  private def initialize[S](spec: FIM[S]): S =
    spec match
      case FIM.UnitF() =>
        ().asInstanceOf[S]

      case FIM.Labeled(_, init) =>
        init()

      case FIM.Prod(left, right) =>
        val l = initialize(left)
        val r = initialize(right)
        (l, r).asInstanceOf[S]

      case FIM.IsoF(base, iso) =>
        val baseVal = initialize(base)
        iso.to(baseVal)

/**
 * Named-tuple (Rec) interpreter for integration with existing FreeScan.
 *
 * This interprets FIM into the Rec (named tuple) type used by FreeScan.
 * Each labeled component becomes a named field.
 *
 * Example:
 *   counter("bytes") ⊗ flag("done")
 *   ~> ("bytes" -> 0L) *: ("done" -> false) *: EmptyTuple
 */
object RecInterpreter extends StateInterpreter:

  type Repr[S] = Rec // Use the existing Rec type from FreeScan

  def alloc[S](spec: FIM[S]): () => Rec =
    () => initializeRec(spec)

  def read[S](spec: FIM[S], repr: Rec): S =
    extractFromRec(spec, repr)

  def write[S](spec: FIM[S], repr: Rec, value: S): Rec =
    updateRec(spec, repr, value)

  /**
   * Initialize a Rec from FIM spec.
   */
  private def initializeRec[S](spec: FIM[S]): Rec =
    spec match
      case FIM.UnitF() =>
        EmptyTuple

      case FIM.Labeled(label, init) =>
        (label, init()) *: EmptyTuple

      case FIM.Prod(left, right) =>
        val lRec = initializeRec(left)
        val rRec = initializeRec(right)
        rec.merge(lRec, rRec)

      case FIM.IsoF(base, _) =>
        // For Rec interpreter, we can't directly apply iso
        // We just initialize the base representation
        initializeRec(base)

  /**
   * Extract typed value from Rec representation.
   */
  private def extractFromRec[S](spec: FIM[S], repr: Rec): S =
    spec match
      case FIM.UnitF() =>
        ().asInstanceOf[S]

      case FIM.Labeled(label, _) =>
        get(repr, label).asInstanceOf[S]

      case FIM.Prod(left, right) =>
        val l = extractFromRec(left, repr)
        val r = extractFromRec(right, repr)
        (l, r).asInstanceOf[S]

      case FIM.IsoF(base, iso) =>
        val baseVal = extractFromRec(base, repr)
        iso.to(baseVal)

  /**
   * Update Rec with new value according to FIM spec.
   */
  private def updateRec[S](spec: FIM[S], repr: Rec, value: S): Rec =
    spec match
      case FIM.UnitF() =>
        repr

      case FIM.Labeled(label, _) =>
        rec.put(repr, label, value).asInstanceOf[Rec]

      case FIM.Prod(left, right) =>
        val (lVal, rVal) = value.asInstanceOf[(Any, Any)]
        val r1           = updateRec[Any](left.asInstanceOf[FIM[Any]], repr, lVal)
        updateRec[Any](right.asInstanceOf[FIM[Any]], r1, rVal)

      case FIM.IsoF(base, iso) =>
        val baseVal = iso.from(value)
        updateRec(base, repr, baseVal)

/**
 * Hybrid interpreter that uses named tuples but preserves type safety.
 *
 * This is useful when you want both:
 * - Named field access (like RecInterpreter)
 * - Type-safe extraction without casts (like ImmutableInterpreter)
 */
object HybridInterpreter:

  /**
   * Materialize a FIM spec into a named tuple type.
   * This would ideally use Scala 3 match types to compute the exact NamedTuple type.
   */
  type NamedRepr[S] = Rec

  /**
   * Convert FIM to named tuple with compile-time field names.
   *
   * In full implementation, this would use match types:
   * type ToNamedTuple[S] <: Tuple = S match
   *   case Unit => EmptyTuple
   *   case FIM.Labeled[s] => (String, s) *: EmptyTuple
   *   case FIM.Prod[a, b] => Concat[ToNamedTuple[a], ToNamedTuple[b]]
   */
  def toNamedTuple[S](spec: FIM[S]): NamedRepr[S] =
    RecInterpreter.alloc(spec)()

/**
 * Optimization: compile-time FIM simplification.
 *
 * These inline methods can be used to simplify FIM expressions at compile time
 * when the structure is known statically.
 */
object CompileTimeOpt:

  /**
   * Eliminate unit products at compile time.
   */
  inline def eliminateUnit[S](inline spec: FIM[S]): FIM[S] =
    FIMOptimize.simplify(spec)

  /**
   * Check if a FIM spec is stateless (reduces to Unit).
   */
  def isStateless[S](spec: FIM[S]): Boolean =
    spec match
      case FIM.UnitF()       => true
      case FIM.IsoF(base, _) => isStateless(base)
      case _                 => false

  /**
   * Count the number of primitive state components.
   */
  def componentCount[S](spec: FIM[S]): Int =
    spec match
      case FIM.UnitF()           => 0
      case FIM.Labeled(_, _)     => 1
      case FIM.Prod(left, right) => componentCount(left) + componentCount(right)
      case FIM.IsoF(base, _)     => componentCount(base)
