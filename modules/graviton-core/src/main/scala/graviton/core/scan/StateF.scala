package graviton.core.scan

/**
 * Free Invariant-Monoidal state description (StateF).
 *
 * Describes state shape without fixing representation:
 * - UnitF: Empty state (monoidal unit)
 * - Field: Named state component
 * - Product: Independent composition (A ⊗ B)
 * - Imap: Invariant transformation via isomorphism
 *
 * This is the "StateF" from the spec, replacing the original FIM.
 * It provides:
 * - Named fields (no accidental tuple nesting)
 * - Type-safe composition
 * - Late binding of representation
 * - Algebraic optimization
 *
 * Laws:
 * - Left unit: product(unit, fa) ≅ fa
 * - Right unit: product(fa, unit) ≅ fa
 * - Associativity: product(product(fa, fb), fc) ≅ product(fa, product(fb, fc))
 * - Iso identity: imap(fa)(id, id) = fa
 * - Iso composition: imap(imap(fa)(f, g))(h, k) = imap(fa)(h ∘ f, g ∘ k)
 */
sealed trait StateF[A]

object StateF:
  
  /**
   * Unit state (ε) - monoidal identity.
   */
  case object UnitF extends StateF[Unit]
  
  /**
   * Named field - primitive state component.
   * 
   * Example: Field[Long]("bytes")
   */
  final case class Field[A](name: String) extends StateF[A]
  
  /**
   * Product of two independent states (A ⊗ B).
   */
  final case class Product[A, B](left: StateF[A], right: StateF[B]) extends StateF[(A, B)]
  
  /**
   * Invariant map via isomorphism.
   * 
   * Allows type-safe relabeling and restructuring.
   */
  final case class Imap[A, B](base: StateF[A], to: A => B, from: B => A) extends StateF[B]
  
  // Smart constructors (IM operations)
  
  /** Unit state */
  def unit: StateF[Unit] = UnitF
  
  /** Named field */
  def field[A](name: String): StateF[A] = Field[A](name)
  
  /** Product of two states */
  def product[A, B](fa: StateF[A], fb: StateF[B]): StateF[(A, B)] = 
    Product(fa, fb)
  
  /** Invariant map */
  def imap[A, B](fa: StateF[A])(to: A => B)(from: B => A): StateF[B] =
    Imap(fa, to, from)
  
  // Common state patterns (from original FIM)
  
  /** Counter (Long) */
  def counter(name: String): StateF[Long] =
    field[Long](name)
  
  /** Boolean flag */
  def flag(name: String): StateF[Boolean] =
    field[Boolean](name)
  
  /** Accumulator */
  def accumulator[A](name: String): StateF[A] =
    field[A](name)
  
  /** Byte array (for hashes) */
  def bytes(name: String): StateF[Array[Byte]] =
    field[Array[Byte]](name)
  
  // Extension methods for composition
  
  extension [A](fa: StateF[A])
    
    /** Infix product operator */
    infix def ⊗[B](fb: StateF[B]): StateF[(A, B)] =
      product(fa, fb)
    
    /** Transform via isomorphism */
    def via[B](to: A => B, from: B => A): StateF[B] =
      imap(fa)(to)(from)

/**
 * Optimization passes for StateF.
 *
 * These preserve semantics while simplifying structure:
 * - Unit elimination: A ⊗ ε → A
 * - Iso fusion: imap(f) >>> imap(g) → imap(g ∘ f)
 * - Dead field elimination: remove unused fields
 */
object StateFOptimize:
  
  /**
   * Simplify a StateF expression using algebraic laws.
   */
  def simplify[S](spec: StateF[S]): StateF[S] =
    spec match
      case StateF.Imap(base, to, from) =>
        base match
          // Iso fusion (not implemented for type safety)
          case StateF.Imap(base2, to2, from2) =>
            StateF.Imap(simplify(base), to, from)
          
          // Identity iso elimination
          case _ if to == identity[Any] && from == identity[Any] =>
            simplify(base).asInstanceOf[StateF[S]]
          
          case _ =>
            StateF.Imap(simplify(base), to, from)
      
      case StateF.Product(left, right) =>
        (simplify(left), simplify(right)) match
          // Left unit: ε ⊗ A ≅ A
          case (StateF.UnitF, r) =>
            r.asInstanceOf[StateF[S]]
          
          // Right unit: A ⊗ ε ≅ A
          case (l, StateF.UnitF) =>
            l.asInstanceOf[StateF[S]]
          
          case (l, r) =>
            StateF.Product(l, r).asInstanceOf[StateF[S]]
      
      case _ => spec
  
  /**
   * Extract all field names from a StateF.
   */
  def extractFields[S](spec: StateF[S]): Set[String] =
    spec match
      case StateF.UnitF => Set.empty
      case StateF.Field(name) => Set(name)
      case StateF.Product(left, right) => 
        extractFields(left) ++ extractFields(right)
      case StateF.Imap(base, _, _) => 
        extractFields(base)
  
  /**
   * Eliminate unused fields (dead code elimination).
   */
  def eliminateDeadFields[S](spec: StateF[S], usedFields: Set[String]): StateF[S] =
    spec match
      case field @ StateF.Field(name) =>
        if usedFields.contains(name) then field
        else StateF.unit.asInstanceOf[StateF[S]]
      
      case StateF.Product(left, right) =>
        val leftOpt = eliminateDeadFields(left, usedFields)
        val rightOpt = eliminateDeadFields(right, usedFields)
        simplify(StateF.Product(leftOpt, rightOpt).asInstanceOf[StateF[S]])
      
      case StateF.Imap(base, to, from) =>
        StateF.Imap(eliminateDeadFields(base, usedFields), to, from)
      
      case _ => spec
  
  /**
   * Check if a StateF is stateless (reduces to Unit).
   */
  def isStateless[S](spec: StateF[S]): Boolean =
    simplify(spec) match
      case StateF.UnitF => true
      case _ => false
  
  /**
   * Count the number of field components.
   */
  def fieldCount[S](spec: StateF[S]): Int =
    spec match
      case StateF.UnitF => 0
      case StateF.Field(_) => 1
      case StateF.Product(left, right) => 
        fieldCount(left) + fieldCount(right)
      case StateF.Imap(base, _, _) => 
        fieldCount(base)

/**
 * Interpreter for StateF to concrete representations.
 *
 * Maps abstract state descriptions to runtime values.
 */
trait StateFInterpreter:
  type Repr[S]
  
  def alloc[S](spec: StateF[S]): () => Repr[S]
  def read[S](spec: StateF[S], repr: Repr[S]): S
  def write[S](spec: StateF[S], repr: Repr[S], value: S): Repr[S]

/**
 * Direct value interpreter (immutable).
 * 
 * Repr[S] = S (zero overhead).
 */
object ImmutableStateFInterpreter extends StateFInterpreter:
  type Repr[S] = S
  
  def alloc[S](spec: StateF[S]): () => S =
    () => initialize(spec)
  
  def read[S](spec: StateF[S], repr: S): S =
    repr
  
  def write[S](spec: StateF[S], repr: S, value: S): S =
    value
  
  private def initialize[S](spec: StateF[S]): S =
    spec match
      case StateF.UnitF =>
        ().asInstanceOf[S]
      
      case StateF.Field(_) =>
        // Fields must have default values; for now use null
        null.asInstanceOf[S]
      
      case StateF.Product(left, right) =>
        val l = initialize(left)
        val r = initialize(right)
        (l, r).asInstanceOf[S]
      
      case StateF.Imap(base, to, _) =>
        to(initialize(base))

/**
 * Named-tuple (Rec) interpreter.
 * 
 * Integrates with existing graviton Rec type.
 */
object RecStateFInterpreter extends StateFInterpreter:
  type Repr[S] = Rec
  
  def alloc[S](spec: StateF[S]): () => Rec =
    () => initializeRec(spec)
  
  def read[S](spec: StateF[S], repr: Rec): S =
    extractFromRec(spec, repr)
  
  def write[S](spec: StateF[S], repr: Rec, value: S): Rec =
    updateRec(spec, repr, value)
  
  private def initializeRec[S](spec: StateF[S]): Rec =
    spec match
      case StateF.UnitF =>
        EmptyTuple
      
      case StateF.Field(name) =>
        (name, null) *: EmptyTuple
      
      case StateF.Product(left, right) =>
        val lRec = initializeRec(left)
        val rRec = initializeRec(right)
        rec.merge(lRec, rRec)
      
      case StateF.Imap(base, _, _) =>
        initializeRec(base)
  
  private def extractFromRec[S](spec: StateF[S], repr: Rec): S =
    spec match
      case StateF.UnitF =>
        ().asInstanceOf[S]
      
      case StateF.Field(name) =>
        get(repr, name).asInstanceOf[S]
      
      case StateF.Product(left, right) =>
        val l = extractFromRec(left, repr)
        val r = extractFromRec(right, repr)
        (l, r).asInstanceOf[S]
      
      case StateF.Imap(base, to, _) =>
        to(extractFromRec(base, repr))
  
  private def updateRec[S](spec: StateF[S], repr: Rec, value: S): Rec =
    spec match
      case StateF.UnitF =>
        repr
      
      case StateF.Field(name) =>
        rec.put(repr, name, value).asInstanceOf[Rec]
      
      case StateF.Product(left, right) =>
        val (lVal, rVal) = value.asInstanceOf[(Any, Any)]
        val r1 = updateRec[Any](left.asInstanceOf[StateF[Any]], repr, lVal)
        updateRec[Any](right.asInstanceOf[StateF[Any]], r1, rVal)
      
      case StateF.Imap(base, _, from) =>
        updateRec(base, repr, from(value))
