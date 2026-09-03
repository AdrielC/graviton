package graviton.runtime.stores

import graviton.core.bytes.{HashAlgo, HashBytes, HashError, HashInput, Hasher}
import graviton.core.keys.BinaryKey
import graviton.core.types.{BlobOffset, BlockCount, BlockIndex, BlockSize, FileSize}
import zio.{Chunk, IO, Ref, UIO, ZIO}

import java.nio.charset.StandardCharsets

/**
 * Versioned, streaming Merkle B-tree for ordered manifest entries.
 *
 * Leaves contain up to [[Fanout]] entries. Branches contain up to [[Fanout]]
 * child summaries. Each summary authenticates its index and byte range, so the
 * root commits to both content identity and manifest topology.
 *
 * One ordered manifest stream owns the builder. `Ref.Synchronized` makes each
 * state transition and hasher use atomic, while the refined index and offset
 * checks reject out-of-order calls. The immutable frontier retains at most one
 * partial node per level and never materializes the complete manifest.
 */
private[stores] object ManifestMerkleTree:

  final val Version: Int = 1
  final val Fanout: Int  = 64

  private val LeafDomain   = ascii("graviton:manifest:merkle-btree:v1:leaf")
  private val BranchDomain = ascii("graviton:manifest:merkle-btree:v1:branch")
  private val RootDomain   = ascii("graviton:manifest:merkle-btree:v1:root")

  sealed trait Error derives CanEqual:
    def message: String

  object Error:
    final case class WrongAlgorithm(actual: HashAlgo) extends Error:
      override def message: String = s"Manifest Merkle B-tree requires sha-256, received ${actual.primaryName}"

    final case class Hashing(cause: HashError) extends Error:
      override def message: String = cause.message

    final case class InvalidEntry(detail: String) extends Error:
      override def message: String = detail

    final case class InvalidState(detail: String) extends Error:
      override def message: String = detail

  final case class Summary(
    digest: HashBytes,
    height: Int,
    firstIndex: BlockIndex,
    entryCount: BlockCount,
    startOffset: BlobOffset,
    endOffset: BlobOffset,
  ):
    def lastIndex: Long = java.lang.Math.addExact(firstIndex, entryCount - 1L)

  private final case class LeafEntry(
    index: BlockIndex,
    key: BinaryKey.Block,
    offset: BlobOffset,
    endOffset: BlobOffset,
  )

  private final case class State(
    pendingLeaves: Vector[LeafEntry],
    levels: Vector[Vector[Summary]],
    observed: BlockCount,
    previousEnd: Option[BlobOffset],
    finished: Boolean,
    peakFrontier: Int,
  ):
    def currentFrontierNodes: Int =
      pendingLeaves.length + levels.iterator.map(_.length).sum

  private object State:
    val empty: State = State(Vector.empty, Vector.empty, BlockCount.Zero, None, finished = false, peakFrontier = 0)

  final class Builder private (hasher: Hasher, state: Ref.Synchronized[State]):
    def entryCount: UIO[BlockCount] =
      state.get.map(_.observed)

    def peakFrontierNodes: UIO[Int] =
      state.get.map(_.peakFrontier)

    def currentFrontierNodes: UIO[Int] =
      state.get.map(_.currentFrontierNodes)

    def add(index: BlockIndex, key: BinaryKey.Block, offset: BlobOffset): IO[Error, Unit] =
      state.modifyZIO { current =>
        ZIO.fromEither(addTo(current, index, key, offset)).map(next => (() -> next))
      }

    def finish(
      rootPrefix: HashInput,
      expectedEntries: BlockCount,
      expectedSize: FileSize,
    ): IO[Error, HashBytes] =
      state.modifyZIO { current =>
        ZIO.fromEither(finishState(current, rootPrefix, expectedEntries, expectedSize))
      }

    private def addTo(
      current: State,
      index: BlockIndex,
      key: BinaryKey.Block,
      offset: BlobOffset,
    ): Either[Error, State] =
      for
        _         <- ensureOpen(current)
        blockSize <- sizeOf(key)
        endOffset <- endOf(offset, blockSize)
        _         <- Either.cond(
                       index == current.observed.toLong,
                       (),
                       Error.InvalidEntry(s"Merkle leaf index $index must equal next index ${current.observed}"),
                     )
        _         <- Either.cond(
                       current.previousEnd.fold(offset == 0L)(end => java.lang.Math.addExact(end, 1L) == offset),
                       (),
                       Error.InvalidEntry(s"Merkle leaf $index is not contiguous with the preceding leaf"),
                     )
        nextCount <- current.observed.next.toRight(Error.InvalidEntry("Manifest exceeds the supported block-count bound"))
        appended   = recordFrontier(
                       current.copy(
                         pendingLeaves = current.pendingLeaves :+ LeafEntry(index, key, offset, endOffset),
                         observed = nextCount,
                         previousEnd = Some(endOffset),
                       )
                     )
        next      <- if appended.pendingLeaves.length == Fanout then flushLeaf(appended) else Right(appended)
      yield next

    private def finishState(
      current: State,
      rootPrefix: HashInput,
      expectedEntries: BlockCount,
      expectedSize: FileSize,
    ): Either[Error, (HashBytes, State)] =
      for
        _              <- ensureOpen(current)
        _              <- Either.cond(current.observed > 0, (), Error.InvalidState("Manifest Merkle B-tree cannot be empty"))
        _              <- Either.cond(
                            current.observed == expectedEntries,
                            (),
                            Error.InvalidState(s"Manifest entry count mismatch: expected $expectedEntries, observed ${current.observed}"),
                          )
        actualSize     <- observedSize(current)
        _              <- Either.cond(
                            actualSize == expectedSize,
                            (),
                            Error.InvalidState(s"Manifest size mismatch: expected $expectedSize, observed $actualSize"),
                          )
        flushed        <- if current.pendingLeaves.nonEmpty then flushLeaf(current) else Right(current)
        collapsed      <- collapse(flushed)
        (summary, next) = collapsed
        root           <- digest(
                            HashInput.concat(
                              Chunk(RootDomain, int32(Version), int32(Fanout), rootPrefix, encodeSummary(summary))
                            )
                          )
      yield root -> next.copy(finished = true)

    private def flushLeaf(current: State): Either[Error, State] =
      val entries = current.pendingLeaves
      for
        _     <- Either.cond(entries.nonEmpty, (), Error.InvalidState("Cannot flush an empty Merkle leaf"))
        hash  <- digest(
                   HashInput.concat(
                     Chunk(LeafDomain, int32(Version), int32(Fanout), int32(entries.length)) ++ entries.map(encodeEntry)
                   )
                 )
        count <- BlockCount.either(entries.length).left.map(Error.InvalidState.apply)
        head   = entries.head
        last   = entries.last
        leaf   = Summary(hash, height = 0, head.index, count, head.offset, last.endOffset)
        next  <- push(current.copy(pendingLeaves = Vector.empty), 0, leaf)
      yield next

    private def push(current: State, level: Int, summary: Summary): Either[Error, State] =
      val extended = current.levels.padTo(level + 1, Vector.empty)
      val pending  = extended(level) :+ summary
      val appended = recordFrontier(current.copy(levels = extended.updated(level, pending)))
      if pending.length < Fanout then Right(appended)
      else
        for
          parent <- branch(level + 1, pending)
          next   <- push(appended.copy(levels = appended.levels.updated(level, Vector.empty)), level + 1, parent)
        yield next

    private def collapse(initial: State): Either[Error, (Summary, State)] =
      def loop(current: State, level: Int): Either[Error, (Summary, State)] =
        val highest = current.levels.lastIndexWhere(_.nonEmpty)
        if level > highest then
          current.levels.iterator.flatMap(_.iterator).toVector match
            case Vector(root) => Right(root -> current)
            case other        => Left(Error.InvalidState(s"Merkle frontier ended with ${other.length} roots"))
        else
          val pending   = current.levels(level)
          val hasHigher = current.levels.iterator.drop(level + 1).exists(_.nonEmpty)
          if pending.nonEmpty && (pending.length > 1 || hasHigher) then
            for
              parent <- branch(level + 1, pending)
              cleared = current.copy(levels = current.levels.updated(level, Vector.empty))
              next   <- push(cleared, level + 1, parent)
              result <- loop(next, level + 1)
            yield result
          else loop(current, level + 1)

      loop(initial, 0)

    private def branch(height: Int, children: Vector[Summary]): Either[Error, Summary] =
      for
        _     <- Either.cond(children.nonEmpty && children.length <= Fanout, (), Error.InvalidState("Invalid Merkle branch fanout"))
        _     <- validateChildren(height, children)
        hash  <- digest(
                   HashInput.concat(
                     Chunk(BranchDomain, int32(Version), int32(Fanout), int32(height), int32(children.length)) ++
                       children.map(encodeSummary)
                   )
                 )
        count <- blockCount(children)
        head   = children.head
        last   = children.last
      yield Summary(hash, height, head.firstIndex, count, head.startOffset, last.endOffset)

    private def validateChildren(height: Int, children: Vector[Summary]): Either[Error, Unit] =
      children.zipWithIndex
        .foldLeft[Either[Error, Option[Summary]]](Right(None)) { case (result, (child, index)) =>
          result.flatMap { previous =>
            for
              _ <- Either.cond(child.height == height - 1, (), Error.InvalidState(s"Merkle child $index has inconsistent height"))
              _ <- Either.cond(
                     previous.forall(prior => prior.lastIndex + 1L == child.firstIndex),
                     (),
                     Error.InvalidState(s"Merkle child $index has a non-contiguous index range"),
                   )
              _ <- Either.cond(
                     previous.forall(prior => prior.endOffset + 1L == child.startOffset),
                     (),
                     Error.InvalidState(s"Merkle child $index has a non-contiguous byte range"),
                   )
            yield Some(child)
          }
        }
        .map(_ => ())

    private def digest(input: HashInput): Either[Error, HashBytes] =
      if hasher.inputSize != 0L then Left(Error.InvalidState("Manifest Merkle hasher was not reset between nodes"))
      else
        input.foreachSegment { segment =>
          val _ = hasher.update(segment.bytes)
        }
        hasher.hash.left.map(Error.Hashing.apply).map(_.bytes)

    private def sizeOf(key: BinaryKey.Block): Either[Error, BlockSize] =
      Either
        .cond(key.bits.size <= Int.MaxValue.toLong, key.bits.size.toInt, s"Block key size ${key.bits.size} exceeds Int capacity")
        .flatMap(BlockSize.either)
        .left
        .map(Error.InvalidEntry.apply)

    private def endOf(offset: BlobOffset, size: BlockSize): Either[Error, BlobOffset] =
      try
        BlobOffset
          .either(java.lang.Math.addExact(offset, size.toLong - 1L))
          .left
          .map(Error.InvalidEntry.apply)
      catch case _: ArithmeticException => Left(Error.InvalidEntry(s"Merkle leaf at offset $offset exceeds the supported offset range"))

    private def observedSize(current: State): Either[Error, Long] =
      current.previousEnd match
        case None      => Right(0L)
        case Some(end) =>
          try Right(java.lang.Math.addExact(end, 1L))
          catch case _: ArithmeticException => Left(Error.InvalidState("Manifest byte size overflow"))

    private def blockCount(children: Vector[Summary]): Either[Error, BlockCount] =
      try
        BlockCount
          .either(children.foldLeft(0)((count, child) => java.lang.Math.addExact(count, child.entryCount)))
          .left
          .map(Error.InvalidState.apply)
      catch case _: ArithmeticException => Left(Error.InvalidState("Merkle branch entry count overflow"))

    private def ensureOpen(current: State): Either[Error, Unit] =
      Either.cond(!current.finished, (), Error.InvalidState("Manifest Merkle B-tree builder is already complete"))

    private def recordFrontier(current: State): State =
      current.copy(peakFrontier = math.max(current.peakFrontier, current.currentFrontierNodes))

  object Builder:
    def make(hasher: Hasher): IO[Error, Builder] =
      if hasher.algo != HashAlgo.Sha256 then ZIO.fail(Error.WrongAlgorithm(hasher.algo))
      else Ref.Synchronized.make(State.empty).map(new Builder(hasher, _))

  def text(value: String): HashInput =
    val bytes = Chunk.fromArray(value.getBytes(StandardCharsets.UTF_8))
    HashInput.concat(Chunk(int32(bytes.length), HashInput.bytes(bytes)))

  def int32(value: Int): HashInput =
    HashInput.bytes(
      Chunk(
        (value >>> 24).toByte,
        (value >>> 16).toByte,
        (value >>> 8).toByte,
        value.toByte,
      )
    )

  def int64(value: Long): HashInput =
    HashInput.bytes(
      Chunk(
        (value >>> 56).toByte,
        (value >>> 48).toByte,
        (value >>> 40).toByte,
        (value >>> 32).toByte,
        (value >>> 24).toByte,
        (value >>> 16).toByte,
        (value >>> 8).toByte,
        value.toByte,
      )
    )

  private def encodeEntry(entry: LeafEntry): HashInput =
    HashInput.concat(
      Chunk(int64(entry.index), text(entry.key.bits.render), int64(entry.offset), int64(entry.endOffset))
    )

  private def encodeSummary(summary: Summary): HashInput =
    HashInput.concat(
      Chunk(
        int32(summary.height),
        int64(summary.firstIndex),
        int64(summary.entryCount),
        int64(summary.startOffset),
        int64(summary.endOffset),
        HashInput.bytes(summary.digest),
      )
    )

  private def ascii(value: String): HashInput =
    HashInput.bytes(Chunk.fromArray(value.getBytes(StandardCharsets.US_ASCII)))
