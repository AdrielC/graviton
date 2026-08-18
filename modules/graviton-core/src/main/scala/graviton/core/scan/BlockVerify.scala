package graviton.core.scan

import graviton.core.bytes.*
import graviton.core.keys.{BinaryKey, KeyBits}
import kyo.Record
import kyo.Record.`~`
import zio.Chunk

/**
 * Block-level verification transducer.
 *
 * Re-hashes each block and compares the result against its expected
 * `BinaryKey.Block`. Used for integrity checks on stored data.
 *
 * Composes naturally with the ingest pipeline:
 * {{{
 * val verify = IngestPipeline.rechunk(blockSize) >>> BlockVerify.verifier(manifest)
 * val (summary, results) = blockStream.run(verify.toSink)
 * assert(summary.failed == 0L)
 * }}}
 */
object BlockVerify:

  /** Result of verifying a single block. */
  sealed trait VerifyResult:
    def index: Long

  object VerifyResult:
    final case class Passed(index: Long, key: BinaryKey.Block)                               extends VerifyResult
    final case class Failed(index: Long, expected: BinaryKey.Block, actual: BinaryKey.Block) extends VerifyResult
    final case class Error(index: Long, message: String)                                     extends VerifyResult

  /**
   * Verify blocks against an ordered sequence of expected keys.
   *
   * Input:  `Chunk[Byte]` (a block)
   * Output: `VerifyResult`
   *
   * For each input block (by position), re-hash the bytes and compare
   * the derived `BinaryKey.Block` against `expectedKeys(index)`.
   *
   * Summary: `Record[("verified" ~ Long) & ("failed" ~ Long) & ("errors" ~ Long)]`
   */
  def verifier(
    expectedKeys: IndexedSeq[BinaryKey.Block],
    algo: HashAlgo = HashAlgo.runtimeDefault,
  ): Transducer[Chunk[Byte], VerifyResult, Record[("verified" ~ Long) & ("failed" ~ Long) & ("errors" ~ Long)]] =
    type S = Record[("verified" ~ Long) & ("failed" ~ Long) & ("errors" ~ Long)]
    new Transducer[Chunk[Byte], VerifyResult, S]:
      type Hot = (Long, Long, Long, Long) // index, verified, failed, errors

      def initHot: Hot = (0L, 0L, 0L, 0L)

      def step(h: Hot, block: Chunk[Byte]): (Hot, Chunk[VerifyResult]) =
        val (idx, verified, failed, errors) = h
        if idx >= expectedKeys.length then
          val result = VerifyResult.Error(idx, s"Block index $idx exceeds expected key count ${expectedKeys.length}")
          ((idx + 1, verified, failed, errors + 1), Chunk.single(result))
        else
          val expected = expectedKeys(idx.toInt)
          val derived  = for
            hasher <- Hasher.hasher(algo, None)
            _       = hasher.update(block.toArray)
            digest <- hasher.digest
            bits   <- KeyBits.create(algo, digest, block.length.toLong)
            key    <- BinaryKey.block(bits)
          yield key
          derived match
            case Right(actual) if actual == expected =>
              ((idx + 1, verified + 1, failed, errors), Chunk.single(VerifyResult.Passed(idx, actual)))
            case Right(actual)                       =>
              ((idx + 1, verified, failed + 1, errors), Chunk.single(VerifyResult.Failed(idx, expected, actual)))
            case Left(msg)                           =>
              ((idx + 1, verified, failed, errors + 1), Chunk.single(VerifyResult.Error(idx, msg)))

      def flush(h: Hot): (Hot, Chunk[VerifyResult]) = (h, Chunk.empty)

      def toSummary(h: Hot): S =
        val (_, verified, failed, errors) = h
        (Record.empty & ("verified" ~ verified) & ("failed" ~ failed) & ("errors" ~ errors)).asInstanceOf[S]

  /**
   * Verify a full blob: rechunk → verify each block against manifest keys.
   *
   * Combines `IngestPipeline.rechunk` with `BlockVerify.verifier` to verify
   * stored blocks from a byte stream:
   *
   * {{{
   * val check = BlockVerify.blobVerifier(blockSize, manifestBlockKeys)
   * val (summary, results) = storedByteStream.run(check.toSink)
   * // summary.verified, summary.failed, summary.blockCount
   * }}}
   */
  def blobVerifier(
    blockSize: Int,
    expectedKeys: IndexedSeq[BinaryKey.Block],
    algo: HashAlgo = HashAlgo.runtimeDefault,
  ) =
    IngestPipeline.rechunk(blockSize) >>> verifier(expectedKeys, algo)

end BlockVerify
