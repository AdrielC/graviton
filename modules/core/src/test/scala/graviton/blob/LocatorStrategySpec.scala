package graviton.blob

import graviton.blob.Types.*
import io.github.iltotore.iron.*
import io.github.iltotore.iron.Constraint
import io.github.iltotore.iron.constraint.all.*
import io.github.iltotore.iron.constraint.numeric
import _root_.zio.test.*

object LocatorStrategySpec extends ZIOSpecDefault:
  inline private def refineOrThrow[A, C](value: A)(using Constraint[A, C]): A :| C =
    value.refineEither[C].fold(err => throw new IllegalArgumentException(err), identity)

  private def algoOf(value: String): Algo                  = refineOrThrow[String, Match["(sha-256|blake3|md5)"]](value)
  private def hexOf(value: String): HexLower               = refineOrThrow[String, Match["[0-9a-f]+"] & MinLength[2]](value)
  private def sizeOf(value: Long): Size                    = refineOrThrow[Long, numeric.Greater[-1]](value)
  private def chunkIndexOf(value: Long): ChunkIndex        = refineOrThrow[Long, numeric.Greater[-1]](value)
  private def mimeOf(value: String): Mime                  =
    refineOrThrow[String, Match["[a-z0-9!#$&^_.+-]+/[a-z0-9!#$&^_.+-]+(;.*)?"]](value)
  private def nonNegativeIntOf(value: Int): NonNegativeInt = refineOrThrow[Int, numeric.GreaterEqual[0]](value)
  private def positiveIntOf(value: Int): PositiveInt       = refineOrThrow[Int, numeric.Greater[0]](value)
  private def pathSegmentOf(value: String): PathSegment    =
    refineOrThrow[String, Match["[^/]+"] & MinLength[1]](value)

  private val sha256String     = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
  private val sha256: HexLower =
    hexOf(sha256String)

  private val key = BinaryKey
    .make(algoOf("sha-256"), sha256, sizeOf(1024L), Some(mimeOf("application/octet-stream")))
    .fold(err => throw new RuntimeException(err), identity)

  private def keyOf(algo: String, hash: String, size: Long, mime: Option[String]): BinaryKey =
    BinaryKey
      .make(algoOf(algo), hexOf(hash), sizeOf(size), mime.map(mimeOf))
      .fold(err => throw new RuntimeException(err), identity)

  def spec =
    suite("LocatorStrategy")(
      test("ShardedByHash derives deterministic paths") {
        val strategy = LocatorStrategy.ShardedByHash(
          scheme = "s3",
          bucket = "example",
          prefix = Some("blobs"),
        )

        val locator = strategy.locatorFor(key)
        assertTrue(
          locator.scheme == "s3",
          locator.bucket == "example",
          locator.path ==
            "blobs/sha-256/01/23/sha-256-0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef-1024~YXBwbGljYXRpb24vb2N0ZXQtc3RyZWFt/",
          strategy.manifestPath(key) ==
            "blobs/sha-256/01/23/sha-256-0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef-1024~YXBwbGljYXRpb24vb2N0ZXQtc3RyZWFt/manifest.json",
          strategy.chunkPath(key, chunkIndexOf(0L)) ==
            "blobs/sha-256/01/23/sha-256-0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef-1024~YXBwbGljYXRpb24vb2N0ZXQtc3RyZWFt/chunks/00000000",
          strategy.chunkDirectoryPath(key) ==
            "blobs/sha-256/01/23/sha-256-0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef-1024~YXBwbGljYXRpb24vb2N0ZXQtc3RyZWFt/chunks/",
        )
      },
      test("ShardedByHash tolerates missing prefix and short shards") {
        val smallKey     = keyOf("blake3", "0a", 0L, None)
        val strategy     = LocatorStrategy.ShardedByHash(
          scheme = "file",
          bucket = "", // e.g. filesystem root
          prefix = None,
          fanOutSegments = nonNegativeIntOf(3),
          segmentLength = positiveIntOf(2),
        )
        val locator      = strategy.locatorFor(smallKey)
        val roundTripped = strategy.keyFor(locator).toOption
        assertTrue(
          locator.path == "blake3/0a/blake3-0a-0/",
          strategy.monolithicObjectPath(smallKey) == "blake3/0a/blake3-0a-0/object.bin",
          roundTripped.contains(smallKey),
        )
      },
      test("ShardedByHash trims user-supplied prefixes and supports directories") {
        val strategy = LocatorStrategy.ShardedByHash(
          scheme = "file",
          bucket = "",
          prefix = Some("/deep//nest/"),
          fanOutSegments = nonNegativeIntOf(0),
          includeTerminalSlash = false,
          patchLogDirectoryName = pathSegmentOf("patches"),
        )

        val locator = strategy.locatorFor(key)
        assertTrue(
          locator.path ==
            "deep/nest/sha-256/sha-256-0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef-1024~YXBwbGljYXRpb24vb2N0ZXQtc3RyZWFt",
          strategy.rootDirectoryPath(key) ==
            "deep/nest/sha-256/sha-256-0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef-1024~YXBwbGljYXRpb24vb2N0ZXQtc3RyZWFt/",
          strategy.patchLogDirectoryPath(key) ==
            "deep/nest/sha-256/sha-256-0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef-1024~YXBwbGljYXRpb24vb2N0ZXQtc3RyZWFt/patches/",
          strategy.chunkDirectoryPath(key) ==
            "deep/nest/sha-256/sha-256-0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef-1024~YXBwbGljYXRpb24vb2N0ZXQtc3RyZWFt/chunks/",
        )
      },
      test("ShardedByHash formats chunk indices with configurable width") {
        val strategy = LocatorStrategy.ShardedByHash(
          scheme = "s3",
          bucket = "example",
          chunkIndexWidth = positiveIntOf(4),
        )

        val formatted = List(
          strategy.chunkPath(key, chunkIndexOf(0L)),
          strategy.chunkPath(key, chunkIndexOf(1L)),
          strategy.chunkPath(key, chunkIndexOf(12L)),
          strategy.chunkPath(key, chunkIndexOf(9999L)),
        )
        assertTrue(
          formatted == List(
            "sha-256/01/23/sha-256-0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef-1024~YXBwbGljYXRpb24vb2N0ZXQtc3RyZWFt/chunks/0000",
            "sha-256/01/23/sha-256-0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef-1024~YXBwbGljYXRpb24vb2N0ZXQtc3RyZWFt/chunks/0001",
            "sha-256/01/23/sha-256-0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef-1024~YXBwbGljYXRpb24vb2N0ZXQtc3RyZWFt/chunks/0012",
            "sha-256/01/23/sha-256-0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef-1024~YXBwbGljYXRpb24vb2N0ZXQtc3RyZWFt/chunks/9999",
          )
        )
      },
      test("ShardedByHash does not emit shard segments when fan-out is zero") {
        val strategy = LocatorStrategy.ShardedByHash(
          scheme = "s3",
          bucket = "example",
          fanOutSegments = nonNegativeIntOf(0),
        )

        val locator = strategy.locatorFor(key)
        assertTrue(
          locator.path.startsWith("sha-256/"),
          !locator.path.contains("/01/"),
        )
      },
      test("ShardedByHash respects includeTerminalSlash flag") {
        val noSlash   = LocatorStrategy.ShardedByHash(
          scheme = "s3",
          bucket = "example",
          includeTerminalSlash = false,
        )
        val withSlash = LocatorStrategy.ShardedByHash(
          scheme = "s3",
          bucket = "example",
          includeTerminalSlash = true,
        )

        assertTrue(
          !noSlash.locatorFor(key).path.endsWith("/"),
          withSlash.locatorFor(key).path.endsWith("/"),
        )
      },
      test("ShardedByHash rejects invalid configuration") {
        val badSegmentLength = 0.refineEither[numeric.Greater[0]]
        val badFanOut        = (-1).refineEither[numeric.GreaterEqual[0]]
        val badDirectory     = "".refineEither[Match["[^/]+"] & MinLength[1]]

        assertTrue(badSegmentLength.isLeft, badFanOut.isLeft, badDirectory.isLeft)
      },
      test("ShardedByHash iso reconstructs keys with and without MIME metadata") {
        val baseStrategy = LocatorStrategy.ShardedByHash(
          scheme = "s3",
          bucket = "example",
          includeTerminalSlash = false,
        )
        val withoutMime  = keyOf("sha-256", sha256String, 1024L, None)
        val withMime     = key

        val locatorNoMime = baseStrategy.locatorFor(withoutMime)
        val locatorMime   = baseStrategy.locatorFor(withMime)

        assertTrue(
          baseStrategy.keyFor(locatorNoMime).contains(withoutMime),
          baseStrategy.keyFor(locatorMime).contains(withMime),
        )
      },
      test("ShardedByHash iso validates locator affinity") {
        val strategy = LocatorStrategy.ShardedByHash(
          scheme = "s3",
          bucket = "example",
          prefix = Some("tenant"),
        )
        val locator  = strategy.locatorFor(key)

        val wrongScheme = locator.copy(scheme = "file")
        val wrongBucket = locator.copy(bucket = "other")
        val wrongPath   = locator.copy(path = s"tenant/${key.algo}/aa/bb/terminal")

        assertTrue(
          strategy.keyFor(locator).contains(key),
          strategy.keyFor(wrongScheme).isLeft,
          strategy.keyFor(wrongBucket).isLeft,
          strategy.keyFor(wrongPath).isLeft,
        )
      },
      test("ShardedByHash extracts keys from derived paths") {
        val strategy = LocatorStrategy.ShardedByHash(
          scheme = "s3",
          bucket = "example",
          prefix = Some("tenant"),
        )
        val locator  = strategy.locatorFor(key)
        val root     = strategy.rootDirectoryPath(key)
        val manifest = strategy.manifestPath(key)
        val mono     = strategy.monolithicObjectPath(key)
        val patchDir = strategy.patchLogDirectoryPath(key)
        val chunkDir = strategy.chunkDirectoryPath(key)
        val chunk0   = strategy.chunkPath(key, chunkIndexOf(0L))

        assertTrue(
          strategy.keyFromLocatorPath(locator.path).contains(key),
          strategy.keyFromRootDirectoryPath(root).contains(key),
          strategy.keyFromManifestPath(manifest).contains(key),
          strategy.keyFromMonolithicObjectPath(mono).contains(key),
          strategy.keyFromPatchLogDirectoryPath(patchDir).contains(key),
          strategy.keyFromChunkDirectoryPath(chunkDir).contains(key),
          strategy.keyAndChunkIndexFromPath(chunk0).contains((key, chunkIndexOf(0L))),
        )
      },
      test("ShardedByHash chunk parsing validates directory and index") {
        val strategy      = LocatorStrategy.ShardedByHash(
          scheme = "s3",
          bucket = "example",
          chunkIndexWidth = positiveIntOf(2),
        )
        val chunkPath     = strategy.chunkPath(key, chunkIndexOf(10L))
        val wrongDirPath  = chunkPath.replace("chunks", "parts")
        val prefixEnd     = chunkPath.lastIndexOf('/')
        val wrongFilePath =
          if prefixEnd >= 0 then s"${chunkPath.substring(0, prefixEnd + 1)}xx"
          else "xx"

        assertTrue(
          strategy.keyAndChunkIndexFromPath(chunkPath).contains((key, chunkIndexOf(10L))),
          strategy.keyAndChunkIndexFromPath(wrongDirPath).isLeft,
          strategy.keyAndChunkIndexFromPath(wrongFilePath).isLeft,
        )
      },
    )
