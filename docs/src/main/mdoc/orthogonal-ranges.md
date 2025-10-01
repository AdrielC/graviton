---
id: orthogonal-ranges
slug: /design/orthogonal-ranges
sidebar_label: Orthogonal Range Engine
sidebar_position: 45
title: Orthogonal Range Engine
description: Generic, safe range handling for Graviton blobs, manifests, and repair flows.
---

# Orthogonal Ranges for Graviton

> A generic, safe, and composable range system that powers manifests, repair jobs, and replica reconciliation across any discrete identifier type.

---

## 1. Why we need a range engine

Graviton has to reason about *absence* just as much as presence:

- multipart uploads land blocks out of order;
- replicas can diverge and heal independently;
- repair jobs stitch together surviving spans from multiple sources;
- manifests must stay compact and future-friendly.

The existing ad-hoc range logic is brittle, over-specialised to byte offsets, and prone to overflow or sentinel bugs. We want a small, orthogonal toolkit that:

1. works for any discrete identifier `A` (bytes, frame IDs, sequence numbers, logical pages, …);
2. never silently overflows at the edges of the domain;
3. can express open/closed/unbounded intent while storing only finite spans;
4. composes with [cats-collections `Diet`](https://github.com/typelevel/cats-collections) for finite set algebra;
5. cooperates with union-find (`DisjointSets`) so that repair logic can reconcile replicas by component;
6. serialises cleanly in manifests and service APIs.

The remainder of this document sketches the primitives, how they interlock, and which invariants keep them safe.

---

## 2. Design pillars

| Pillar | Consequence |
| --- | --- |
| **Genericity** | Every range structure is type-parameterised on `A`; the only requirement is a discrete total order. |
| **Boundary safety** | `next`/`prev` return `Option[A]`. Callers must acknowledge the edges; we never wrap. |
| **End-exclusive storage** | File math stays in `[start, end)` form (easier for byte lengths, chunk arithmetic, JSON serialisation). |
| **Algebird-style intent** | We model open/closed/unbounded bounds explicitly and normalise to finite closed spans only when necessary. |
| **Orthogonality** | Discrete navigation, interval algebra, set algebra, and connectivity each live in separate modules. |
| **Observability** | Every abstraction surfaces helpers for rendering, JSON, and diagnostics to make debugging miserable states simpler. |

---

## 3. Discrete domains

A discrete domain is a totally ordered type where we can move to the next or previous element safely.

```scala
import cats.Order

/** A totally ordered, stepwise-discrete domain with optional bounds. */
trait DiscreteDomain[A] {
  def order: Order[A]

  /** Next greater element, or None if at the top boundary. */
  def next(a: A): Option[A]

  /** Previous smaller element, or None if at the bottom boundary. */
  def prev(a: A): Option[A]

  /** True iff `y` is the immediate successor of `x`. */
  def adjacent(x: A, y: A): Boolean = next(x).contains(y)

  /** Optional global bounds. `None` means “unbounded”. */
  def minValue: Option[A] = None
  def maxValue: Option[A] = None
}
```

Implementation example for bytes-as-`Long`:

```scala
import cats.kernel.instances.long.given

given DiscreteDomain[Long] with {
  val order: Order[Long] = Order[Long]
  def next(a: Long) = if (a == Long.MaxValue) None else Some(a + 1)
  def prev(a: Long) = if (a == Long.MinValue) None else Some(a - 1)
  override val minValue = Some(Long.MinValue)
  override val maxValue = Some(Long.MaxValue)
}
```

Key points:

- The API *forces* callers to account for edges. We never “wrap around”.
- `minValue`/`maxValue` let us express bounded domains (e.g. fixed-width frame IDs) without inventing sentinels.
- `adjacent` is defined in terms of `next`, so any custom invariants carry through automatically.

### Open issues

- For huge integer-like types (128-bit identifiers) we will want a derived domain from `spire.math.SafeLong` or a custom big-int wrapper.
- For `UUID` or other composite keys, `next`/`prev` need careful design; consider exposing monotone iterators instead.

---

## 4. Interval algebra (intent level)

Intervals express *what a caller means* before we normalise to finite spans. Inspired by Algebird, we keep explicit bound types.

```scala
sealed trait Bound[+A]
object Bound {
  case object NegInf                      extends Bound[Nothing]
  final case class Closed[A](value: A)    extends Bound[A]
  final case class Open[A](value: A)      extends Bound[A]
  case object PosInf                      extends Bound[Nothing]
}

final case class Interval[A](lower: Bound[A], upper: Bound[A]) {
  def mapMonotone[B](f: A => B): Interval[B] =
    Interval(Interval.mapBound(lower)(f), Interval.mapBound(upper)(f))

  def intersect(that: Interval[A])(using O: Order[A]): Interval[A] =
    Interval(Interval.maxLower(lower, that.lower), Interval.minUpper(upper, that.upper))

  /** Resolve to a finite closed pair [lo, hi] if representable in the domain. */
  def toClosed(using D: DiscreteDomain[A]): Option[(A, A)] =
    for {
      lo <- Interval.lowerAsClosed(lower)
      hi <- Interval.upperAsClosed(upper)
      if D.order.lteqv(lo, hi)
    } yield (lo, hi)

  def isEmpty(using D: DiscreteDomain[A]): Boolean =
    toClosed.exists { case (l, h) => D.order.gt(l, h) }
}
```

Helpers (written once to keep the case analysis local):

```scala
object Interval {
  def closed[A](l: A, h: A): Interval[A] = Interval(Bound.Closed(l), Bound.Closed(h))
  def open[A](l: A, h: A): Interval[A] = Interval(Bound.Open(l), Bound.Open(h))
  def closedOpen[A](l: A, h: A): Interval[A] = Interval(Bound.Closed(l), Bound.Open(h))
  def openClosed[A](l: A, h: A): Interval[A] = Interval(Bound.Open(l), Bound.Closed(h))
  def unbounded[A]: Interval[A] = Interval(Bound.NegInf, Bound.PosInf)

  def lowerAsClosed[A](b: Bound[A])(using D: DiscreteDomain[A]): Option[A] = b match {
    case Bound.NegInf      => D.minValue
    case Bound.Closed(v)   => Some(v)
    case Bound.Open(v)     => D.next(v)
    case Bound.PosInf      => None
  }

  def upperAsClosed[A](b: Bound[A])(using D: DiscreteDomain[A]): Option[A] = b match {
    case Bound.PosInf      => D.maxValue
    case Bound.Closed(v)   => Some(v)
    case Bound.Open(v)     => D.prev(v)
    case Bound.NegInf      => None
  }

  def maxLower[A](x: Bound[A], y: Bound[A])(using O: Order[A]): Bound[A] = (x, y) match {
    case (Bound.NegInf, b) => b
    case (b, Bound.NegInf) => b
    case (Bound.PosInf, _) => Bound.PosInf
    case (_, Bound.PosInf) => Bound.PosInf
    case (Bound.Closed(a), Bound.Closed(b)) => if (O.gteqv(a, b)) x else y
    case (Bound.Open(a),   Bound.Open(b))   => if (O.gteqv(a, b)) x else y
    case (Bound.Open(a),   Bound.Closed(b)) => if (O.gt(a, b)) x else y
    case (Bound.Closed(a), Bound.Open(b))   => if (O.gt(a, b)) x else y
  }

  def minUpper[A](x: Bound[A], y: Bound[A])(using O: Order[A]): Bound[A] = (x, y) match {
    case (Bound.PosInf, b) => b
    case (b, Bound.PosInf) => b
    case (Bound.NegInf, _) => Bound.NegInf
    case (_, Bound.NegInf) => Bound.NegInf
    case (Bound.Closed(a), Bound.Closed(b)) => if (O.lteqv(a, b)) x else y
    case (Bound.Open(a),   Bound.Open(b))   => if (O.lteqv(a, b)) x else y
    case (Bound.Open(a),   Bound.Closed(b)) => if (O.lteqv(a, b)) x else y
    case (Bound.Closed(a), Bound.Open(b))   => if (O.lt(a, b)) x else y
  }

  def mapBound[A, B](bound: Bound[A])(f: A => B): Bound[B] = bound match {
    case Bound.NegInf      => Bound.NegInf
    case Bound.PosInf      => Bound.PosInf
    case Bound.Closed(v)   => Bound.Closed(f(v))
    case Bound.Open(v)     => Bound.Open(f(v))
  }
}
```

The `maxLower`/`minUpper` logic mirrors Algebird’s definitions; the implementation can live in a small pattern-matching helper table. The important improvement here is that the `Option`-returning helpers keep the open/closed math isolated—callers never have to repeat the case analysis.

### Why keep intent and storage separate?

- Callers can express “everything after X” with `Interval(Bound.Closed(X), Bound.PosInf)` without allocating giant sentinels.
- Repair flows can intersect request windows before normalisation, avoiding premature rounding.
- Only the conversion layer touches `DiscreteDomain.next/prev`, minimising the chance of off-by-one bugs.

---

## 5. Spans (storage level)

Once an interval is finite we store it as a `[start, end)` span.

```scala
/** End-exclusive span [start, end). */
final case class Span[A](start: A, endExclusive: A) {
  def isEmpty(using O: Order[A]): Boolean = O.gteqv(start, endExclusive)
}

object Span {
  /** Convert an interval that resolved to a closed [lo, hi] into [lo, hi+1). */
  def fromClosed[A](lo: A, hi: A)(using D: DiscreteDomain[A]): Option[Span[A]] =
    D.next(hi).map(Span(lo, _))

  def toInterval[A](s: Span[A]): Interval[A] =
    Interval.closedOpen(s.start, s.endExclusive)
}
```

Improvements over the original draft:

- `isEmpty` is explicit; we can fail-fast when ingest tries to add a zero-length chunk.
- `fromClosed` is the *only* place that increments `hi`, preventing accidental overflow.
- We can add smart constructors (e.g. `Span.exactLength(start, length)`) for readability inside blob math.

---

## 6. Range sets backed by Diet

`RangeSet[A]` wraps a `Diet[A]` (which stores inclusive ranges) and exposes an end-exclusive API.

```scala
import cats.Order
import cats.collections.{Diet, Range => IncRange}
import cats.syntax.all.*

final case class RangeSet[A] private (diet: Diet[A]) {
  def add(span: Span[A])(using D: DiscreteDomain[A]): RangeSet[A] =
    normalise(span).fold(this)(inc => RangeSet(diet.addRange(inc)))

  def remove(span: Span[A])(using D: DiscreteDomain[A]): RangeSet[A] =
    normalise(span).fold(this)(inc => RangeSet(diet.removeRange(inc)))

  def addAll(spans: Iterable[Span[A]])(using D: DiscreteDomain[A]): RangeSet[A] =
    spans.foldLeft(this)(_.add(_))

  def contains(span: Span[A])(using D: DiscreteDomain[A]): Boolean =
    normalise(span).exists(diet.containsRange)

  def containsPoint(a: A): Boolean = diet.contains(a)

  /** Disjoint spans in ascending order. */
  def intervals(using D: DiscreteDomain[A]): List[Span[A]] =
    diet.toList.flatMap { inc => D.next(inc.end).map(end => Span(inc.start, end)) }

  def union(that: RangeSet[A]): RangeSet[A] = RangeSet(this.diet ++ that.diet)
  def intersect(that: RangeSet[A]): RangeSet[A] = RangeSet(this.diet & that.diet)
  def difference(that: RangeSet[A]): RangeSet[A] = RangeSet(this.diet -- that.diet)

  /** Holes inside a requested interval. */
  def holes(within: Interval[A])(using D: DiscreteDomain[A]): List[Span[A]] =
    within.toClosed.toList.flatMap { case (lo, hi) =>
      val requested = Diet.fromRange(IncRange(lo, hi))
      val missing   = requested -- diet
      missing.toList.flatMap { inc => D.next(inc.end).map(end => Span(inc.start, end)) }
    }

  def isComplete(within: Interval[A])(using D: DiscreteDomain[A]): Boolean = holes(within).isEmpty

  def min(using O: Order[A]): Option[A] = diet.min
  def maxExclusive(using D: DiscreteDomain[A]): Option[A] = diet.max.flatMap(D.next)

  private def normalise(span: Span[A])(using D: DiscreteDomain[A]): Option[IncRange[A]] =
    if span.isEmpty(using D.order) then None
    else D.prev(span.endExclusive).map(endInclusive => IncRange(span.start, endInclusive))
}

object RangeSet {
  def empty[A: Order]: RangeSet[A] = RangeSet(Diet.empty[A])

  def fromSpans[A](spans: Iterable[Span[A]])(using D: DiscreteDomain[A]): RangeSet[A] =
    spans.foldLeft(empty[A])(_.add(_))
}
```

Differences versus the initial sketch:

- `normalise` handles empty spans early and shares the `[lo, hi]` conversion logic.
- `containsPoint` no longer requires an `Order`; `Diet` already embeds it.
- `holes` reuses Diet diff, but the result is still converted back to end-exclusive spans.

### Serialisation helpers

We store spans as JSON pairs `(start, endExclusive)`.

```scala
import zio.json.*

given [A: JsonEncoder: JsonDecoder](using D: DiscreteDomain[A]): JsonCodec[RangeSet[A]] =
  new JsonCodec[RangeSet[A]] {
    def encodeJson(rs: RangeSet[A], indent: Option[Int]) =
      JsonEncoder[List[(A, A)]].encodeJson(rs.intervals.map(s => (s.start, s.endExclusive)), indent)

    def decodeJson(json: ast.Json) =
      JsonDecoder[List[(A, A)]].decodeJson(json).flatMap { pairs =>
        pairs.traverse { case (s, e) =>
          Either.fromOption(Option.when(D.order.lt(s, e))(Span(s, e)), s"invalid span ($s, $e)")
        }.map(RangeSet.fromSpans)
      }
  }
```

We validate that `start < endExclusive` during decoding so manifests cannot contain degenerate intervals.

---

## 7. Disjoint sets (connectivity)

`DisjointSets[A]` is our union–find abstraction. The range engine does not depend on it directly, but repair flows will:

1. Build a `DisjointSets[ReplicaId]` to understand which replicas share the same physical data.
2. Fold each component’s `RangeSet` with `union` to find coverage.
3. Call `holes` on the expected interval to schedule repairs.

Keep the API persistent (pure functional maps) so we can snapshot state per reconciliation round.

---

## 8. Integration scenarios

### Multipart PUT

1. Maintain a running offset in the relevant domain (`Long` for bytes).
2. After each successful chunk, add `Span(offset, offset + size)` to the manifest’s `RangeSet`.
3. On completion, compare against the expected interval `Interval.closedOpen(0L, blobSize)`.
4. If complete, drop `presentRanges` to keep manifests compact; otherwise persist the spans.

### GET with partial replicas

1. Intersect the requested interval with the replica’s `RangeSet`.
2. Serve the intersection if policy allows partial reads; otherwise return `206` or `416` depending on gaps.
3. Surface diagnostics using `holes` to explain missing coverage.

### Repair

1. For a logical blob, union all known replica sets.
2. Compute gaps relative to the blob’s expected interval.
3. Use range differences to plan copy jobs (e.g. `sourceRangeSet.difference(targetRangeSet)`).
4. Update manifests incrementally using the JSON codec above.

---

## 9. Invariants and safety checks

- `Span` must satisfy `start < endExclusive`.
- `RangeSet` never stores empty spans (enforced by `normalise`).
- `DiscreteDomain.next/prev` return `None` at bounds, preventing overflow.
- Zero-sized blobs resolve to an empty `RangeSet`; completeness is `true` by definition.
- Chunkers must emit strictly positive lengths.
- JSON decoding validates every span; malformed manifests fail fast.

---

## 10. Testing strategy

| Area | Example property / test |
| --- | --- |
| **DiscreteDomain** | `next(max).isEmpty`, `prev(min).isEmpty`, `adjacent(x, next(x))`. |
| **Interval** | Intersection commutativity/associativity; `mapMonotone` preserves order; `toClosed` round-trips open/closed boundaries. |
| **Span** | `fromClosed` paired with `toInterval` recovers the original interval. |
| **RangeSet** | `union`/`intersect`/`difference` match Diet behaviour; `holes` + `isComplete` round-trip requested intervals. |
| **JSON** | Serialise → deserialise equals identity for random span sets. |
| **Integration** | Property test that merging replica sets and scheduling repairs never loses coverage. |

Use ScalaCheck for the algebraic laws and targeted unit tests for manifest codecs.

---

## 11. Deliverables checklist

- [ ] `DiscreteDomain[A]` type class with instances for `Long`, `Int`, and at least one domain-specific wrapper (`FrameId`).
- [ ] `Bound[A]` + `Interval[A]` with helpers, normalisation, and intersection semantics.
- [ ] `Span[A]` end-exclusive representation with smart constructors.
- [ ] `RangeSet[A]` on Diet, plus JSON codecs and diagnostics.
- [ ] Persistent `DisjointSets[A]` with JSON support.
- [ ] Wiring into BlobStore ingest/retrieve/repair flows.
- [ ] ScalaCheck + munit test suites covering the properties above.
- [ ] Documentation updates: this page plus code samples in the blob ingest guide.

---

## 12. Open questions & follow-ups

- Should the manifest expose both “present” and “quarantined” spans? If so, model them as separate `RangeSet`s.
- Do we need interval arithmetic on streaming APIs (e.g. lazy iterators of spans) for extremely fragmented replicas?
- Would a specialised `CompressedRangeSet` be valuable for mostly-complete replicas (run-length encoding over Diet)?
- Explore whether we can auto-derive `DiscreteDomain` from `spire.algebra.AdditiveMonoid` + `Eq` instances.

---

## 13. TL;DR

- Model discrete navigation, interval intent, and stored spans separately.
- Keep everything type-parametric and safe at boundaries.
- Back finite subsets with Diet and expose end-exclusive spans.
- Compose with union–find for replica reconciliation.
- Serialise spans as JSON pairs for manifests and APIs.

This orthogonal range engine gives Graviton a reusable foundation for bytes today and whatever discrete domains we invent tomorrow.
