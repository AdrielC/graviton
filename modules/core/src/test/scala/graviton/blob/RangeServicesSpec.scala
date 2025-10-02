package graviton.blob

import graviton.*
import graviton.Ranges.*
import graviton.collections.DisjointSets
import graviton.blob.Types.*
import io.github.iltotore.iron.autoRefine
import zio.*
import zio.test.*

object RangeServicesSpec extends ZIOSpecDefault:
  private val sampleHash: HexLower = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
  private val sampleKey: BinaryKey =
    BinaryKey.make("sha-256", sampleHash, 10L, None).fold(err => throw new RuntimeException(err), identity)

  private val locatorA = BlobLocator("file", "bucket", "alpha")
  private val locatorB = BlobLocator("s3", "bucket", "beta")

  def spec =
    suite("RangeServices")(
      test("RangeTracker accumulates ranges per locator") {
        val rangeA = ByteRange(0L, 4L)
        val rangeB = ByteRange(4L, 10L)
        for
          tracker <- RangeTracker.inMemory
          _       <- tracker.add(locatorA, rangeA)
          _       <- tracker.addMany(locatorA, Chunk(rangeB))
          summary <- tracker.summary(locatorA)
          holes   <- tracker.holes(locatorA, Length.unsafe(10L))
        yield assertTrue(summary.intervals == List(ByteRange(0L, 10L)), holes.isEmpty)
      },
      test("RangeTracker reports holes for incomplete blobs") {
        val range = ByteRange(2L, 6L)
        for
          tracker <- RangeTracker.inMemory
          _       <- tracker.add(locatorA, range)
          holes   <- tracker.holes(locatorA, Length.unsafe(8L))
        yield assertTrue(holes == List(ByteRange(0L, 2L), ByteRange(6L, 8L)))
      },
      test("ReplicaIndex merges coverage across replicas") {
        val partA = ByteRange(0L, 5L)
        val partB = ByteRange(5L, 10L)
        for
          tracker <- RangeTracker.inMemory
          index   <- ReplicaIndex.inMemory(tracker)
          _       <- tracker.add(locatorA, partA)
          _       <- tracker.add(locatorB, partB)
          _       <- index.union(sampleKey, locatorA, locatorB)
          members <- index.replicas(sampleKey)
          result  <- index.completeness(sampleKey, Length.unsafe(10L))
        yield assertTrue(members == Set(locatorA, locatorB), result == (true -> Nil))
      },
      test("ReplicaIndex exposes holes when combined coverage is incomplete") {
        val partA = ByteRange(0L, 4L)
        val partB = ByteRange(6L, 10L)
        for
          tracker <- RangeTracker.inMemory
          index   <- ReplicaIndex.inMemory(tracker)
          _       <- tracker.add(locatorA, partA)
          _       <- tracker.add(locatorB, partB)
          _       <- index.union(sampleKey, locatorA, locatorB)
          result  <- index.completeness(sampleKey, Length.unsafe(10L))
        yield assertTrue(result == (false, List(ByteRange(4L, 6L))))
      },
      test("DisjointSets tracks connectivity and components") {
        val initial               = DisjointSets.empty[String]
        val afterUnion            = initial.union("a", "b").union("b", "c")
        val (normalized, members) = afterUnion.componentMembers("a")
        val (_, connected)        = normalized.connected("a", "c")
        assertTrue(members == Set("a", "b", "c"), connected)
      },
    )
