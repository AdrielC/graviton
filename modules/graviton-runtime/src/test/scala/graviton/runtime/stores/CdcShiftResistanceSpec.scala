package graviton.runtime.stores

import graviton.core.keys.BinaryKey
import graviton.core.types.*
import graviton.streams.Chunker
import zio.*
import zio.stream.*
import zio.test.*

/**
 * Demonstrates FastCDC's **shift-resistance** property — the key insight behind
 * content-defined chunking and the reason CDC-based deduplication is effective.
 *
 * When bytes are inserted near the start of a file, fixed-size chunking invalidates
 * every block after the insertion point (because all boundaries shift). Content-defined
 * chunking, by contrast, re-synchronises within a few blocks and leaves the vast majority
 * of blocks identical. This means that a small edit to a large file produces almost 100%
 * deduplication on the unchanged portions.
 *
 * The test:
 *   1. Generates a 128 KiB pseudo-random byte array.
 *   2. Creates a modified copy with 37 bytes inserted near the start (byte 1024).
 *   3. Ingests both through the CAS pipeline with FastCDC (min=256, avg=1024, max=4096).
 *   4. Compares the block-key sets: CDC shares most blocks; fixed loses almost all.
 */
object CdcShiftResistanceSpec extends ZIOSpecDefault:

  private val dataSize    = 128 * 1024
  private val insertPoint = 1024
  private val insertSize  = 37

  private def pseudoRandom(size: Int, seed: Long = 0xcafebabeL): Array[Byte] =
    val arr   = Array.ofDim[Byte](size)
    var state = seed
    var i     = 0
    while i < size do
      state = state * 6364136223846793005L + 1442695040888963407L
      arr(i) = (state >>> 33).toByte
      i += 1
    arr

  private val original: Chunk[Byte] =
    Chunk.fromArray(pseudoRandom(dataSize))

  private val modified: Chunk[Byte] =
    val arr       = pseudoRandom(dataSize)
    val insertion = pseudoRandom(insertSize, seed = 0xdeadbeefL)
    val result    = Array.ofDim[Byte](dataSize + insertSize)
    java.lang.System.arraycopy(arr, 0, result, 0, insertPoint)
    java.lang.System.arraycopy(insertion, 0, result, insertPoint, insertSize)
    java.lang.System.arraycopy(arr, insertPoint, result, insertPoint + insertSize, dataSize - insertPoint)
    Chunk.fromArray(result)

  private def blockKeys(
    data: Chunk[Byte],
    chunker: Chunker,
  ): ZIO[Any, Any, Set[BinaryKey.Block]] =
    for
      blockStore <- InMemoryBlockStore.make
      repo       <- InMemoryBlobManifestRepo.make
      blobStore   = new CasBlobStore(blockStore, repo)
      result     <- Chunker.locally(chunker) {
                      ZStream.fromChunk(data).run(blobStore.put())
                    }
      blobKey     = result.key.asInstanceOf[BinaryKey.Blob]
      manifest   <- repo.get(blobKey).someOrFail(new NoSuchElementException("manifest"))
      keys        = manifest.entries.collect { case e if e.key.isInstanceOf[BinaryKey.Block] => e.key.asInstanceOf[BinaryKey.Block] }.toSet
    yield keys

  override def spec: Spec[TestEnvironment, Any] =
    suite("CDC Shift-Resistance Demo")(
      test("FastCDC preserves most blocks after a mid-file insertion") {
        val cdc = Chunker.fastCdc(min = 256, avg = 1024, max = 4096)

        for
          origKeys <- blockKeys(original, cdc)
          modKeys  <- blockKeys(modified, cdc)

          shared   = origKeys.intersect(modKeys)
          origOnly = origKeys -- modKeys
          modOnly  = modKeys -- origKeys

          sharedPct = shared.size.toDouble / origKeys.size * 100
          _        <- ZIO.logInfo(s"CDC: ${origKeys.size} original blocks, ${modKeys.size} modified blocks")
          _        <- ZIO.logInfo(s"CDC: ${shared.size} shared (${f"$sharedPct%.1f"}%%), ${origOnly.size} lost, ${modOnly.size} new")
        yield assertTrue(
          sharedPct > 80.0,
          origOnly.size < origKeys.size / 4,
        )
      },
      test("fixed chunking loses most blocks after a near-start insertion") {
        val fixed = Chunker.fixed(UploadChunkSize(1024))

        for
          origKeys <- blockKeys(original, fixed)
          modKeys  <- blockKeys(modified, fixed)

          shared   = origKeys.intersect(modKeys)
          origOnly = origKeys -- modKeys
          modOnly  = modKeys -- origKeys

          sharedPct = shared.size.toDouble / origKeys.size * 100
          _        <- ZIO.logInfo(s"Fixed: ${origKeys.size} original blocks, ${modKeys.size} modified blocks")
          _        <- ZIO.logInfo(s"Fixed: ${shared.size} shared (${f"$sharedPct%.1f"}%%), ${origOnly.size} lost, ${modOnly.size} new")
        yield assertTrue(
          sharedPct < 10.0
        )
      },
      test("CDC dedup ratio is high when re-ingesting a slightly modified file") {
        val cdc = Chunker.fastCdc(min = 256, avg = 1024, max = 4096)

        for
          blockStore <- InMemoryBlockStore.make
          repo       <- InMemoryBlobManifestRepo.make
          blobStore   = new CasBlobStore(blockStore, repo)

          r1 <- Chunker.locally(cdc) {
                  ZStream.fromChunk(original).run(blobStore.put())
                }
          r2 <- Chunker.locally(cdc) {
                  ZStream.fromChunk(modified).run(blobStore.put())
                }

          _ <- ZIO.logInfo(s"First ingest:  ${r1.stats.totalBytes} bytes, ${r1.stats.blockCount} blocks, ${r1.stats.freshBlocks} fresh")
          _ <- ZIO.logInfo(
                 s"Second ingest: ${r2.stats.totalBytes} bytes, ${r2.stats.blockCount} blocks, " +
                   s"${r2.stats.freshBlocks} fresh, ${r2.stats.duplicateBlocks} dedup, ratio=${f"${r2.stats.dedupRatio * 100}%.1f"}%%"
               )
        yield assertTrue(
          r1.stats.freshBlocks == r1.stats.blockCount,
          r1.stats.duplicateBlocks == 0,
          r2.stats.duplicateBlocks > r2.stats.freshBlocks,
          r2.stats.dedupRatio > 0.5,
        )
      },
    )
