package graviton.core.scan

import scala.annotation.targetName
import scala.compiletime.{erasedValue, constValue, summonInline}
import scala.compiletime.ops.int.*
import scala.Tuple.*

/**
 * Type-level named-tuple machinery for scan state.
 * 
 * Records are tuples of (label, value) pairs where labels are singleton strings.
 * Provides type-level operations: Get, Put, Merge, Concat that avoid EmptyTuple pollution.
 */

/** Named-field encoded as a singleton String label and value. */
type Field[K <: String & Singleton, V] = (K, V)

/** A record is just a Tuple of Field[…]. Order is preserved. */
type Rec = Tuple

/** Empty record alias for clarity. */
type Ø = EmptyTuple

/** IsEmpty tuple witness. */
type IsEmpty[T <: Tuple] = T match
  case EmptyTuple => true
  case _          => false

/** Append A ++ B, dropping EmptyTuple on either side. */
infix type ++[A <: Tuple, B <: Tuple] <: Tuple = (A, B) match
  case (EmptyTuple, EmptyTuple) => EmptyTuple
  case (EmptyTuple, b *: bs) => b *: bs
  case (a *: as, EmptyTuple) => a *: as
  case (a *: as, b *: bs)    => a *: (as ++ (b *: bs))

/** Lookup a field by label. */
type Get[A <: Rec, K <: String & Singleton] = A match
  case (K, v) *: _       => v
  case _ *: tail         => Get[tail, K]
  case EmptyTuple        => Nothing

/** Value-level get - runtime implementation with type-safe interface */
def get[A <: Rec, K <: String & Singleton](r: A, k: K): Get[A, K] =
  r match
    case r: NonEmptyTuple =>
      val (key, value) = r.head.asInstanceOf[(String, Any)]
      if (k == key) then
        value.asInstanceOf[Get[A, K]]
      else
        get[Tuple.Tail[A] & Rec, K](r.tail.asInstanceOf[Tuple.Tail[A] & Rec], k).asInstanceOf[Get[A, K]]
    case EmptyTuple =>
      throw new NoSuchElementException(s"Key $k not found in record")

/** Insert a field if label not present, else replace. */
type Put[A <: Rec, F <: Field[?, ?]] <: Rec = F match
  case (k, v) => PutImpl[A, k & String & Singleton, v]

type PutImpl[A <: Rec, K <: String & Singleton, V] <: Rec = A match
  case EmptyTuple                => (K, V) *: EmptyTuple
  case (K, any) *: tail          => (K, V) *: tail
  case head *: tail              => head *: PutImpl[tail, K, V]

/** Merge records (right-biased on key collisions), used by +++/split/merge. */
type Merge[A <: Rec, B <: Rec] <: Rec = B match
  case EmptyTuple     => A
  case (k, v) *: tail => Merge[Put[A, (k, v)], tail]

/** Value-level helpers */
object rec:
  inline def empty: Ø = EmptyTuple
  
  inline def field[K <: String & Singleton, V](k: K, v: V): Field[K, V] = 
    (k, v).asInstanceOf[Field[K, V]]
  
  def put[A <: Rec, K <: String & Singleton, V](a: A, k: K, v: V): Put[A, (K, V)] =
    a match
      case EmptyTuple => 
        ((k, v) *: EmptyTuple).asInstanceOf[Put[A, (K, V)]]
      case a: NonEmptyTuple =>
        val (key, value) = a.head.asInstanceOf[(String, Any)]
        if (k == key) then
          ((k, v) *: a.tail).asInstanceOf[Put[A, (K, V)]]
        else
          val h = a.head
          val t = put[Tuple.Tail[A] & Rec, K, V](a.tail.asInstanceOf[Tuple.Tail[A] & Rec], k, v)
          (h *: t).asInstanceOf[Put[A, (K, V)]]
  
  /** Merge two records, right-biased */
  def merge[A <: Rec, B <: Rec](a: A, b: B): Merge[A, B] =
    b match
      case EmptyTuple => a.asInstanceOf[Merge[A, B]]
      case b: NonEmptyTuple =>
        val (k, v) = b.head.asInstanceOf[(String, Any)]
        // Runtime merge - type safety maintained by return type
        val updated = putRaw(a, k, v)
        merge[A, Tuple.Tail[B] & Rec](updated, b.tail.asInstanceOf[Tuple.Tail[B] & Rec]).asInstanceOf[Merge[A, B]]
  
  /** Runtime put helper */
  private def putRaw[A <: Rec](a: A, k: String, v: Any): A =
    a match
      case EmptyTuple =>
        ((k, v) *: EmptyTuple).asInstanceOf[A]
      case a: NonEmptyTuple =>
        val (key, value) = a.head.asInstanceOf[(String, Any)]
        if (k == key) then
          ((k, v) *: a.tail).asInstanceOf[A]
        else
          (a.head *: putRaw(a.tail.asInstanceOf[Rec], k, v)).asInstanceOf[A]
