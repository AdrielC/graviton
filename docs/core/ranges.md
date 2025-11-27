# Ranges & Boundaries

Graviton uses a sophisticated range algebra for tracking blob spans, managing partial retrievals, and coordinating replication.

## Core Types

### Bound

A single point in the byte space:

```scala
opaque type Bound = Long

object Bound:
  def apply(value: Long): Bound = value
  
  extension (b: Bound)
    def value: Long = b
    def +(offset: Long): Bound = Bound(b + offset)
    def -(offset: Long): Bound = Bound(b - offset)

given Ordering[Bound] = Ordering.by(_.value)
```

### Interval

A closed range `[start, end]`:

```scala
final case class Interval(start: Bound, end: Bound):
  require(start.value <= end.value, "Invalid interval")
  
  def length: Long = end.value - start.value + 1
  def contains(point: Long): Boolean = 
    point >= start.value && point <= end.value
  def overlaps(other: Interval): Boolean =
    !(end.value < other.start.value || start.value > other.end.value)
  def merge(other: Interval): Option[Interval] =
    if overlaps(other) || adjacent(other) then
      Some(Interval(
        start = Bound(math.min(start.value, other.start.value)),
        end = Bound(math.max(end.value, other.end.value))
      ))
    else None

object Interval:
  given Schema[Interval] = DeriveSchema.gen
  given Ordering[Interval] = Ordering.by(_.start)
```

### Span

An offset-length representation (equivalent to Interval):

```scala
final case class Span(offset: Long, length: Long):
  require(offset >= 0 && length >= 0, "Invalid span")
  
  def end: Long = offset + length - 1
  def toInterval: Interval = Interval(Bound(offset), Bound(end))
  def contains(point: Long): Boolean = 
    point >= offset && point < offset + length
  def slice(start: Long, len: Long): Option[Span] =
    if start >= offset && start + len <= offset + length then
      Some(Span(start, len))
    else None

object Span:
  given Schema[Span] = DeriveSchema.gen
  
  def fromInterval(interval: Interval): Span =
    Span(interval.start.value, interval.length)
```

## RangeSet Algebra

### Construction

```scala
final case class RangeSet(intervals: Chunk[Interval]):
  require(isNormalized, "RangeSet must be normalized")
  
  private def isNormalized: Boolean =
    intervals == intervals.sorted.foldLeft(Chunk.empty[Interval]) {
      case (acc, interval) =>
        acc.lastOption match
          case Some(last) if last.adjacent(interval) || last.overlaps(interval) =>
            acc.init :+ last.merge(interval).get
          case _ =>
            acc :+ interval
    }

object RangeSet:
  val empty: RangeSet = RangeSet(Chunk.empty)
  
  def single(interval: Interval): RangeSet = 
    RangeSet(Chunk.single(interval))
  
  def fromSpans(spans: Chunk[Span]): RangeSet =
    RangeSet(spans.map(_.toInterval)).normalize
```

### Union

Combine two range sets:

```scala
extension (rs: RangeSet)
  def union(other: RangeSet): RangeSet =
    RangeSet((rs.intervals ++ other.intervals).sorted).normalize
  
  def |(other: RangeSet): RangeSet = union(other)

// Example
val range1 = RangeSet.single(Interval(Bound(0), Bound(100)))
val range2 = RangeSet.single(Interval(Bound(50), Bound(150)))
val merged = range1 | range2
// Result: RangeSet([0, 150])
```

### Intersection

Find overlapping ranges:

```scala
extension (rs: RangeSet)
  def intersect(other: RangeSet): RangeSet =
    val overlaps = for
      i1 <- rs.intervals
      i2 <- other.intervals
      if i1.overlaps(i2)
    yield Interval(
      Bound(math.max(i1.start.value, i2.start.value)),
      Bound(math.min(i1.end.value, i2.end.value))
    )
    RangeSet(overlaps).normalize
  
  def &(other: RangeSet): RangeSet = intersect(other)

// Example
val range1 = RangeSet.single(Interval(Bound(0), Bound(100)))
val range2 = RangeSet.single(Interval(Bound(50), Bound(150)))
val overlap = range1 & range2
// Result: RangeSet([50, 100])
```

### Difference

Remove ranges:

```scala
extension (rs: RangeSet)
  def difference(other: RangeSet): RangeSet =
    rs.intervals.foldLeft(rs) { (acc, interval) =>
      other.intervals.foldLeft(Chunk.single(interval)) { (remaining, remove) =>
        remaining.flatMap(_.subtract(remove))
      }
    }
  
  def \(other: RangeSet): RangeSet = difference(other)

private extension (i: Interval)
  def subtract(other: Interval): Chunk[Interval] =
    if !overlaps(other) then Chunk.single(i)
    else if other.start.value <= start.value && other.end.value >= end.value then
      Chunk.empty  // Completely covered
    else if other.start.value > start.value && other.end.value < end.value then
      Chunk(
        Interval(start, Bound(other.start.value - 1)),
        Interval(Bound(other.end.value + 1), end)
      )
    else if other.start.value <= start.value then
      Chunk.single(Interval(Bound(other.end.value + 1), end))
    else
      Chunk.single(Interval(start, Bound(other.start.value - 1)))
```

### Complement

Invert within a universe:

```scala
extension (rs: RangeSet)
  def complement(universe: Interval): RangeSet =
    RangeSet.single(universe).difference(rs)

// Example: Find missing ranges
val total = Interval(Bound(0), Bound(1000))
val downloaded = RangeSet(Chunk(
  Interval(Bound(0), Bound(100)),
  Interval(Bound(200), Bound(300))
))
val missing = downloaded.complement(total)
// Result: RangeSet([101, 199], [301, 1000])
```

## Practical Applications

### Partial Download Tracking

```scala
final case class DownloadState(
  total: Span,
  completed: RangeSet,
  pending: RangeSet
):
  def progress: Double = 
    completed.totalBytes.toDouble / total.length
  
  def markComplete(span: Span): DownloadState =
    val interval = span.toInterval
    copy(
      completed = completed.union(RangeSet.single(interval)),
      pending = pending.difference(RangeSet.single(interval))
    )
  
  def nextChunk(size: Long): Option[Span] =
    pending.intervals.headOption.map { interval =>
      Span(interval.start.value, math.min(size, interval.length))
    }

object DownloadState:
  def initial(total: Span): DownloadState =
    DownloadState(
      total = total,
      completed = RangeSet.empty,
      pending = RangeSet.single(total.toInterval)
    )
```

### Replication Gap Analysis

```scala
def findReplicationGaps(
  expected: RangeSet,
  replica1: RangeSet,
  replica2: RangeSet
): RangeSet =
  val covered = replica1.union(replica2)
  expected.difference(covered)

// Example
val blob = RangeSet.single(Interval(Bound(0), Bound(10000)))
val replica1 = RangeSet.single(Interval(Bound(0), Bound(5000)))
val replica2 = RangeSet.single(Interval(Bound(6000), Bound(10000)))
val gaps = findReplicationGaps(blob, replica1, replica2)
// Result: RangeSet([5001, 5999]) — missing range!
```

### Byte Range Requests

HTTP Range header support:

```scala
def parseRangeHeader(header: String, totalSize: Long): Option[RangeSet] =
  header.stripPrefix("bytes=").split(",").map(_.trim).foldLeft(Option(RangeSet.empty)) {
    case (Some(acc), range) =>
      range.split("-") match
        case Array(start, end) if start.nonEmpty && end.nonEmpty =>
          Some(acc.union(RangeSet.single(
            Interval(Bound(start.toLong), Bound(end.toLong))
          )))
        case Array(start, "") if start.nonEmpty =>
          Some(acc.union(RangeSet.single(
            Interval(Bound(start.toLong), Bound(totalSize - 1))
          )))
        case Array("", suffix) if suffix.nonEmpty =>
          val len = suffix.toLong
          Some(acc.union(RangeSet.single(
            Interval(Bound(totalSize - len), Bound(totalSize - 1))
          )))
        case _ => None
    case _ => None
  }

// Examples
parseRangeHeader("bytes=0-999", 10000)     // [0, 999]
parseRangeHeader("bytes=500-", 10000)      // [500, 9999]
parseRangeHeader("bytes=-500", 10000)      // [9500, 9999]
parseRangeHeader("bytes=0-99,200-299", 10000)  // [0, 99], [200, 299]
```

## Optimizations

### Lazy Normalization

Defer merging until needed:

```scala
final case class LazyRangeSet(
  intervals: Chunk[Interval],
  normalized: Boolean = false
):
  private lazy val norm: RangeSet = RangeSet(intervals)
  
  def union(other: LazyRangeSet): LazyRangeSet =
    LazyRangeSet(intervals ++ other.intervals, normalized = false)
  
  def force: RangeSet = norm
```

### Interval Tree

For large range sets with frequent queries:

```scala
sealed trait IntervalTree:
  def contains(point: Long): Boolean
  def query(interval: Interval): Chunk[Interval]

final case class IntervalNode(
  interval: Interval,
  max: Bound,
  left: IntervalTree,
  right: IntervalTree
) extends IntervalTree

case object IntervalLeaf extends IntervalTree:
  def contains(point: Long): Boolean = false
  def query(interval: Interval): Chunk[Interval] = Chunk.empty
```

### Sparse Representation

For very large blobs with few gaps:

```scala
final case class SparseRangeSet(
  total: Interval,
  gaps: RangeSet  // Store missing ranges instead
):
  def contains(point: Long): Boolean =
    total.contains(point) && !gaps.contains(point)
  
  def completed: RangeSet =
    RangeSet.single(total).difference(gaps)
```

## Performance Characteristics

| Operation | Time Complexity | Space |
|-----------|----------------|-------|
| Union | O(n + m) | O(n + m) |
| Intersection | O(n × m) | O(min(n, m)) |
| Difference | O(n × m) | O(n) |
| Contains | O(log n) with tree | O(1) amortized |
| Normalize | O(n log n) | O(n) |

## Testing Utilities

```scala
object RangeSetGen:
  val bound: Gen[Any, Bound] = 
    Gen.long(0, Long.MaxValue).map(Bound(_))
  
  val interval: Gen[Any, Interval] =
    for
      start <- bound
      length <- Gen.long(1, 1000)
    yield Interval(start, Bound(start.value + length))
  
  val rangeSet: Gen[Any, RangeSet] =
    Gen.chunkOf(interval).map(RangeSet(_))

// Property tests
test("union is commutative") {
  check(rangeSet, rangeSet) { (rs1, rs2) =>
    rs1.union(rs2) == rs2.union(rs1)
  }
}

test("intersection is associative") {
  check(rangeSet, rangeSet, rangeSet) { (rs1, rs2, rs3) =>
    rs1.intersect(rs2).intersect(rs3) == rs1.intersect(rs2.intersect(rs3))
  }
}
```

## See Also

- **[Schema & Types](./schema.md)** — Type definitions
- **[Scans & Events](./scans.md)** — Span-based scanning
- **[Backends](../runtime/backends.md)** — Range-based storage
- **[Replication](../runtime/replication.md)** — Gap repair

::: warning
Always normalize RangeSets after mutations to maintain invariants!
:::
