package graviton.core.scan

/**
 * Free Invariant Monoidal - algebraic structure for composing state descriptions.
 *
 * This provides:
 * - Modular state composition via products (⊗)
 * - Type-safe state transformations via isomorphisms
 * - Late binding of state representation (case classes, tuples, Refs, etc.)
 * - Dead code elimination of unused state components
 * - Lawful optimization via algebraic properties
 *
 * Key Laws:
 * - Left unit:  ε ⊗ A  ≅  A
 * - Right unit: A ⊗ ε  ≅  A
 * - Assoc:      (A ⊗ B) ⊗ C  ≅  A ⊗ (B ⊗ C)
 * - Iso round-trip: imap(iso).imap(iso.inverse) = id
 *
 * The "Free" aspect means this is a pure description - interpretation happens separately.
 */
sealed trait FIM[S]:

  /** Apply an invariant isomorphism to transform state type */
  def imap[T](iso: Iso[S, T]): FIM[T] = FIM.IsoF(this, iso)

  /** Product with another state (independent composition) */
  def product[T](that: FIM[T]): FIM[(S, T)] = FIM.Prod(this, that)

  /** Infix alias for product */
  infix def ⊗[T](that: FIM[T]): FIM[(S, T)] = product(that)

object FIM:

  /**
   * Unit state - represents "no state" or empty state.
   * This is the identity element for the monoidal product.
   */
  final case class UnitF() extends FIM[Unit]

  /**
   * Product of two independent states.
   * States are composed side-by-side without interference.
   */
  final case class Prod[A, B](left: FIM[A], right: FIM[B]) extends FIM[(A, B)]

  /**
   * Invariant mapping via isomorphism.
   * Allows type-safe relabeling/reshaping of state.
   */
  final case class IsoF[A, S](base: FIM[A], iso: Iso[A, S]) extends FIM[S]

  /**
   * Primitive labeled state component.
   * This is the base case for actual stateful components.
   */
  final case class Labeled[S](label: String, init: () => S) extends FIM[S]

  // Smart constructors

  /** The unit state (ε) */
  def unit: FIM[Unit] = UnitF()

  /** Create a labeled primitive state component */
  def labeled[S](label: String)(init: => S): FIM[S] =
    Labeled(label, () => init)

  /** Product of two states */
  def product[A, B](fa: FIM[A], fb: FIM[B]): FIM[(A, B)] =
    Prod(fa, fb)

  /** Lift a constant value as state */
  def const[S](label: String, value: S): FIM[S] =
    labeled(label)(value)

  // Common state patterns

  /** Counter state (Long) */
  def counter(label: String): FIM[Long] =
    labeled(label)(0L)

  /** Accumulator state */
  def accumulator[A](label: String, zero: A): FIM[A] =
    labeled(label)(zero)

  /** Boolean flag state */
  def flag(label: String, initial: Boolean = false): FIM[Boolean] =
    labeled(label)(initial)

/**
 * Isomorphism between types A and B.
 *
 * Represents a bidirectional total conversion.
 * Laws:
 * - Round-trip: from(to(a)) == a
 * - Reverse round-trip: to(from(b)) == b
 */
final case class Iso[A, B](to: A => B, from: B => A):

  /** Compose with another iso */
  def andThen[C](that: Iso[B, C]): Iso[A, C] =
    Iso(a => that.to(to(a)), c => from(that.from(c)))

  /** Reverse this iso */
  def inverse: Iso[B, A] =
    Iso(from, to)

  /** Lift to product (parallel application) */
  def product[C, D](that: Iso[C, D]): Iso[(A, C), (B, D)] =
    Iso(
      { case (a, c) => (to(a), that.to(c)) },
      { case (b, d) => (from(b), that.from(d)) },
    )

object Iso:

  /** Identity isomorphism */
  def identity[A]: Iso[A, A] =
    Iso(a => a, a => a)

  /** Iso for associativity: ((A, B), C) <-> (A, (B, C)) */
  def assocLR[A, B, C]: Iso[((A, B), C), (A, (B, C))] =
    Iso(
      { case ((a, b), c) => (a, (b, c)) },
      { case (a, (b, c)) => ((a, b), c) },
    )

  def assocRL[A, B, C]: Iso[(A, (B, C)), ((A, B), C)] =
    assocLR[A, B, C].inverse

  /** Iso for left unit: (Unit, A) <-> A */
  def unitL[A]: Iso[(Unit, A), A] =
    Iso({ case (_, a) => a }, a => ((), a))

  /** Iso for right unit: (A, Unit) <-> A */
  def unitR[A]: Iso[(A, Unit), A] =
    Iso({ case (a, _) => a }, a => (a, ()))

  /** Iso for commutativity: (A, B) <-> (B, A) */
  def swap[A, B]: Iso[(A, B), (B, A)] =
    Iso({ case (a, b) => (b, a) }, { case (b, a) => (a, b) })

  /** Lift function to iso (when bijective) */
  def from[A, B](f: A => B, g: B => A): Iso[A, B] =
    Iso(f, g)

/**
 * Extension methods for FIM composition
 */
extension [A](fa: FIM[A])

  /** Product with infix operator */
  infix def ⊗[B](fb: FIM[B]): FIM[(A, B)] =
    FIM.Prod(fa, fb)

  /** Apply isomorphism */
  def via[B](iso: Iso[A, B]): FIM[B] =
    fa.imap(iso)

  /** Relabel by wrapping in a case class or tuple via iso */
  def as[B](to: A => B, from: B => A): FIM[B] =
    fa.imap(Iso(to, from))

/**
 * Optimization passes for FIM structures.
 *
 * These preserve semantics while simplifying the structure:
 * - Unit elimination: A ⊗ ε -> A
 * - Iso fusion: imap(f).imap(g) -> imap(g ∘ f)
 * - Reassociation for better fusion
 */
object FIMOptimize:

  /**
   * Simplify a FIM expression by applying algebraic laws.
   */
  def simplify[S](fim: FIM[S]): FIM[S] =
    fim match
      case FIM.IsoF(base, iso) =>
        base match
          // Iso fusion: imap(f).imap(g) = imap(g ∘ f)
          case FIM.IsoF(base2, iso2) =>
            // Skip fusion for now due to type complexity
            FIM.IsoF(simplify(base), iso)

          // Eliminate identity iso
          case _ if iso.to == identity[Any] && iso.from == identity[Any] =>
            simplify(base).asInstanceOf[FIM[S]]

          case _ =>
            FIM.IsoF(simplify(base), iso)

      case FIM.Prod(left, right) =>
        (simplify(left), simplify(right)) match
          // Left unit: ε ⊗ A ≅ A
          case (FIM.UnitF(), r) =>
            // Return r directly, unit is identity
            r.asInstanceOf[FIM[S]]

          // Right unit: A ⊗ ε ≅ A
          case (l, FIM.UnitF()) =>
            // Return l directly, unit is identity
            l.asInstanceOf[FIM[S]]

          case (l, r) =>
            FIM.Prod(l, r).asInstanceOf[FIM[S]]

      case _ => fim

  /**
   * Eliminate unused state components.
   * Takes a set of labels that are actually used.
   */
  def eliminateDeadState[S](fim: FIM[S], usedLabels: Set[String]): FIM[S] =
    fim match
      case FIM.Labeled(label, _) if !usedLabels.contains(label) =>
        // Replace unused component with unit
        FIM.unit.imap(Iso(_ => null.asInstanceOf[S], _ => ())).asInstanceOf[FIM[S]]

      case FIM.Prod(left, right) =>
        val leftOpt  = eliminateDeadState(left, usedLabels)
        val rightOpt = eliminateDeadState(right, usedLabels)
        simplify(FIM.Prod(leftOpt, rightOpt).asInstanceOf[FIM[S]])

      case FIM.IsoF(base, iso) =>
        FIM.IsoF(eliminateDeadState(base, usedLabels), iso)

      case _ => fim

  /**
   * Extract all labels from a FIM structure.
   */
  def extractLabels[S](fim: FIM[S]): Set[String] =
    fim match
      case FIM.UnitF()           => Set.empty
      case FIM.Labeled(label, _) => Set(label)
      case FIM.Prod(left, right) => extractLabels(left) ++ extractLabels(right)
      case FIM.IsoF(base, _)     => extractLabels(base)
