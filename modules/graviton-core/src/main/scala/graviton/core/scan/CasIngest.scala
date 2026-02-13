package graviton.core.scan

import graviton.core.bytes.*
import graviton.core.keys.{BinaryKey, KeyBits}
import kyo.Record
import kyo.Record.`~`
import zio.Chunk

/**
 * CAS-level transducers for content-addressed ingest.
 *
 * Bridges the byte-level `IngestPipeline` transducers to content-addressed
 * domain types (`BinaryKey.Block`, per-block digests). These compose
 * naturally with `>>>` and `&&&`:
 *
 * {{{
 * val casPipeline = IngestPipeline.countHashRechunk(blockSize) >>> CasIngest.blockKeyDeriver()
 * }}}
 *
 * Hot state is tuples of primitives. Records are only constructed at flush.
 */
object CasIngest:

  /**
   * Per-block key derivation transducer.
   *
   * Input:  `Chunk[Byte]` (a rechunked block)
   * Output: `KeyedBlock` (the block bytes + its CAS key)
   *
   * For each input block:
   *   1. Hash the block bytes independently (per-block digest)
   *   2. Derive `KeyBits` from algo + digest + block size
   *   3. Derive `BinaryKey.Block` from the `KeyBits`
   *   4. Emit a `KeyedBlock` pairing the block bytes with its key
   *
   * Summary: `Record["blocksKeyed" ~ Long]`
   */
  def blockKeyDeriver(
    algo: HashAlgo = HashAlgo.runtimeDefault
  ): Transducer[Chunk[Byte], KeyedBlock, Record["blocksKeyed" ~ Long]] =
    type S = Record["blocksKeyed" ~ Long]
    new Transducer[Chunk[Byte], KeyedBlock, S]:
      type Hot = Long
      def initHot: Long = 0L

      def step(h: Long, block: Chunk[Byte]): (Long, Chunk[KeyedBlock]) =
        if block.isEmpty then (h, Chunk.empty)
        else
          val result = for
            hasher <- Hasher.hasher(algo, None)
            _       = hasher.update(block.toArray)
            digest <- hasher.digest
            bits   <- KeyBits.create(algo, digest, block.length.toLong)
            key    <- BinaryKey.block(bits)
          yield KeyedBlock(key, block)
          result match
            case Right(kb) => (h + 1, Chunk.single(kb))
            case Left(_)   => (h, Chunk.empty)

      def flush(h: Long): (Long, Chunk[KeyedBlock]) = (h, Chunk.empty)

      def toSummary(h: Long): S =
        (Record.empty & ("blocksKeyed" ~ h)).asInstanceOf[S]

  /**
   * A block paired with its content-addressed key.
   *
   * This is the output of the per-block keying stage, ready to be
   * turned into a `CanonicalBlock` (which lives in graviton-runtime).
   */
  final case class KeyedBlock(
    key: BinaryKey.Block,
    payload: Chunk[Byte],
  ):
    def size: Int = payload.length

  /**
   * The full CAS ingest pipeline: count + hash + rechunk + blockKey.
   *
   * Takes raw `Chunk[Byte]` elements and produces `KeyedBlock`s with
   * a rich summary containing all fields from all stages:
   *
   *   - `totalBytes`: total bytes seen
   *   - `digestHex`: hex digest of the entire stream
   *   - `hashBytes`: bytes hashed
   *   - `blockCount`: blocks produced by rechunker
   *   - `rechunkFill`: leftover bytes in rechunk buffer
   *   - `blocksKeyed`: blocks that received CAS keys
   */
  def pipeline(
    blockSize: Int,
    algo: HashAlgo = HashAlgo.runtimeDefault,
  ) =
    IngestPipeline.countHashRechunk(blockSize, algo) >>> blockKeyDeriver(algo)

end CasIngest
