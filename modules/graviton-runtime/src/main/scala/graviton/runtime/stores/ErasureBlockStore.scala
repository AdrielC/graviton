package graviton.runtime.stores

import graviton.core.attributes.BinaryAttributes
import graviton.core.bytes.Hasher
import graviton.core.keys.BinaryKey
import graviton.runtime.metrics.{MetricKeys, MetricsRegistry}
import graviton.runtime.model.*
import zio.*
import zio.stream.*

/**
 * Fixed 2+1 XOR erasure coding across exactly three independent targets.
 *
 * A canonical block is at most 16 MiB. Encoding retains the source plus three
 * shards of at most 8 MiB each. A quorum read races the three bounded shards
 * and cancels the remaining read after two verified results. At most one third
 * shard can already be queued, so ordinary reconstruction has a conservative
 * 48 MiB per-block ceiling. Whole blobs never enter this store and remain
 * streaming through the CAS pipeline.
 */
final class ErasureBlockStore private (
  targets: Chunk[ErasureBlockStore.Target],
  metrics: MetricsRegistry,
  preferredFailureDomain: Option[String],
) extends ConvergentBlockStore:
  import ErasureBlockStore.*

  override def putBlock(block: CanonicalBlock, plan: BlockWritePlan = BlockWritePlan()): IO[StoreError, StoredBlock] =
    (for
      fragments <- Xor21Codec.encode(block)
      results   <- ZIO.foreachPar(targets) { target =>
                     target.store.put(block.key, fragments(target.index)).either.flatMap { result =>
                       metrics
                         .counter(
                           MetricKeys.ErasureWritesTotal,
                           Map("target" -> target.store.name, "outcome" -> result.fold(_ => "failed", _ => "succeeded")),
                         )
                         .as(target -> result)
                     }
                   }
      successes  = results.collect { case (_, Right(status)) => status }
      _         <- ZIO.fail(WriteThresholdFailed(successes.length)).when(successes.length < DataShards)
      status     = if successes.contains(BlockStoredStatus.Fresh) then BlockStoredStatus.Fresh else BlockStoredStatus.Duplicate
    yield StoredBlock(block.key, block.size, status))
      .mapError(StoreError.fromThrowable(StoreOperation.PutBlock, retryUnknown = true))

  override def putBlocks(plan: BlockWritePlan = BlockWritePlan()): BlockSink =
    ZSink
      .foldLeftZIO(WriteAcc.empty) { (acc, block: CanonicalBlock) =>
        putBlock(block, plan).flatMap(stored => acc.append(block, stored))
      }
      .mapZIO(_.result)
      .mapError(StoreError.fromThrowable(StoreOperation.PutBlock, retryUnknown = true))
      .ignoreLeftover

  override def get(key: BinaryKey.Block): ZStream[Any, StoreError, Byte] =
    ZStream
      .unwrap {
        readAvailable(key, stopAfter = DataShards).flatMap { read =>
          for
            block <- Xor21Codec.decode(key, read.fragments)
            kind   = if read.fragments.keySet == Set(0, 1) then "systematic" else "reconstructed"
            _     <- metrics.counter(MetricKeys.ErasureReadsTotal, Map("path" -> kind))
            _     <- metrics.counter(MetricKeys.ErasureReconstructionsTotal, Map("path" -> kind)).when(kind == "reconstructed")
          yield ZStream.fromChunk(block.bytes)
        }
      }
      .mapError(StoreError.fromThrowable(StoreOperation.GetBlock, retryUnknown = true))

  override def exists(key: BinaryKey.Block): IO[StoreError, Boolean] =
    readAvailable(key, stopAfter = DataShards)
      .as(true)
      .catchSome { case _: NotEnoughShards => ZIO.succeed(false) }
      .mapError(StoreError.fromThrowable(StoreOperation.ExistsBlock, retryUnknown = true))

  override def healthCheck: IO[StoreError, Unit] =
    ZIO
      .foreachPar(targets)(_.store.healthCheck.either)
      .flatMap { checks =>
        val healthy = checks.count(_.isRight)
        metrics.gauge(MetricKeys.ErasureHealthyTargets, healthy.toDouble, Map.empty) *>
          ZIO.fail(WriteThresholdFailed(healthy)).when(healthy < DataShards).unit
      }
      .mapError(StoreError.fromThrowable(StoreOperation.HealthCheck, retryUnknown = true))

  override def converge(key: BinaryKey.Block): IO[StoreError, RepairConvergence] =
    (for
      read      <- readAvailable(key, stopAfter = TotalShards)
      block     <- Xor21Codec.decode(key, read.fragments)
      fragments <- Xor21Codec.encode(block)
      repaired  <- ZIO.foldLeft(targets.filterNot(target => read.fragments.contains(target.index)))(Map.empty[String, String] -> 0) {
                     case ((failures, count), target) =>
                       target.store.repair(key, fragments(target.index)).either.flatMap {
                         case Right(_)    =>
                           metrics.counter(MetricKeys.ErasureRepairsTotal, Map("target" -> target.store.name, "outcome" -> "repaired")) *>
                             ZIO.succeed(failures -> (count + 1))
                         case Left(error) =>
                           val detail = Option(error.getMessage).getOrElse(error.getClass.getSimpleName)
                           metrics.counter(MetricKeys.ErasureRepairsTotal, Map("target" -> target.store.name, "outcome" -> "failed")) *>
                             ZIO.succeed(failures.updated(target.store.name, detail) -> count)
                       }
                   }
    yield RepairConvergence(read.fragments.size, repaired._2, repaired._1))
      .mapError(StoreError.fromThrowable(StoreOperation.Repair, retryUnknown = true))

  private def readAvailable(key: BinaryKey.Block, stopAfter: Int): Task[ReadSet] =
    ZIO
      .fromEither(Xor21Codec.fragmentLength(key.bits.size))
      .mapError(new IllegalArgumentException(_))
      .flatMap(expectedLength => readAvailable(key, expectedLength, stopAfter))

  private def readAvailable(key: BinaryKey.Block, expectedLength: Int, stopAfter: Int): Task[ReadSet] =
    val ordered = preferLocal(targets)

    def collect(
      queue: Queue[Either[(Target, Throwable), (Target, ErasureFragment)]],
      remaining: Int,
      found: Map[Int, ErasureFragment],
      failures: Map[String, String],
    ): Task[ReadSet] =
      if found.size >= stopAfter || remaining == 0 then
        if found.size >= DataShards then ZIO.succeed(ReadSet(found, failures))
        else ZIO.fail(NotEnoughShards(key, found.size, failures))
      else
        queue.take.flatMap {
          case Right((target, fragment)) =>
            val locality =
              preferredFailureDomain.fold("unconfigured")(domain => if target.store.failureDomain == domain then "local" else "remote")
            metrics.counter(MetricKeys.ErasureShardReadsTotal, Map("target" -> target.store.name, "locality" -> locality)) *>
              collect(queue, remaining - 1, found.updated(target.index, fragment), failures)
          case Left((target, error))     =>
            val detail = Option(error.getMessage).getOrElse(error.getClass.getSimpleName)
            collect(queue, remaining - 1, found, failures.updated(target.store.name, detail))
        }

    ZIO.scoped {
      for
        queue <- Queue.unbounded[Either[(Target, Throwable), (Target, ErasureFragment)]]
        _     <- ZIO.foreachDiscard(ordered) { target =>
                   target.store
                     .get(key, target.index, expectedLength)
                     .either
                     .flatMap {
                       case Right(fragment) => queue.offer(Right(target -> fragment))
                       case Left(error)     => queue.offer(Left(target -> error))
                     }
                     .forkScoped
                 }
        read  <- collect(queue, ordered.length, Map.empty, Map.empty)
      yield read
    }

  private def preferLocal(values: Chunk[Target]): Chunk[Target] =
    preferredFailureDomain match
      case None         => values
      case Some(domain) => values.sortBy(target => if target.store.failureDomain == domain then 0 else 1)

object ErasureBlockStore:
  val DataShards   = 2
  val ParityShards = 1
  val TotalShards  = DataShards + ParityShards

  final case class Target(index: Int, store: ErasureFragmentStore)

  final case class WriteThresholdFailed(succeeded: Int)
      extends RuntimeException(s"2+1 erasure write requires two targets, succeeded=$succeeded total=3")

  final case class NotEnoughShards(key: BinaryKey.Block, available: Int, failures: Map[String, String])
      extends RuntimeException(
        s"Cannot reconstruct ${key.bits.render}: available=$available required=2 failed=${failures.keys.toList.sorted.mkString(",")}"
      )

  def make(
    stores: Chunk[ErasureFragmentStore],
    metrics: MetricsRegistry = MetricsRegistry.noop,
    preferredFailureDomain: Option[String] = None,
  ): Either[String, ErasureBlockStore] =
    val ordered = stores.sortBy(_.name)
    Either.cond(
      ordered.length == TotalShards &&
        ordered.map(_.name).distinct.length == TotalShards &&
        ordered.map(_.failureDomain).distinct.length == TotalShards,
      new ErasureBlockStore(ordered.zipWithIndex.map((store, index) => Target(index, store)), metrics, preferredFailureDomain),
      "erasure-2-1 requires three uniquely named stores in three distinct failure domains",
    )

  private final case class ReadSet(fragments: Map[Int, ErasureFragment], failures: Map[String, String])

  private final case class WriteAcc(
    entries: ChunkBuilder[BlockManifestEntry],
    stored: ChunkBuilder[StoredBlock],
    offset: Long,
    index: Long,
  ):
    def append(block: CanonicalBlock, result: StoredBlock): Task[WriteAcc] =
      ZIO
        .fromEither(BlockManifestEntry.make(index, offset, block.key, block.size.value))
        .mapError(new IllegalArgumentException(_))
        .map { entry =>
          entries += entry
          stored += result
          copy(offset = offset + block.size.value.toLong, index = index + 1L)
        }

    def result: Task[BlockBatchResult] =
      ZIO.fromEither(BlockManifest.build(entries.result())).mapError(new IllegalArgumentException(_)).map { manifest =>
        BlockBatchResult(manifest, stored.result(), Chunk.empty, Chunk.empty)
      }

  private object WriteAcc:
    val empty: WriteAcc = WriteAcc(ChunkBuilder.make(), ChunkBuilder.make(), 0L, 0L)

private[stores] object Xor21Codec:
  def fragmentLength(originalLength: Long): Either[String, Int] =
    Either.cond(
      originalLength >= 1L && originalLength <= 16777216L,
      ((originalLength + 1L) / 2L).toInt,
      s"2+1 erasure coding requires a canonical block length within 1..16777216, received $originalLength",
    )

  def encode(block: CanonicalBlock): Task[Chunk[ErasureFragment]] =
    ZIO.attempt {
      val length = fragmentLength(block.size.value.toLong).fold(message => throw new IllegalStateException(message), identity)
      val first  = Array.ofDim[Byte](length)
      val second = Array.ofDim[Byte](length)
      val parity = Array.ofDim[Byte](length)
      val source = block.bytes
      var index  = 0
      while index < length do
        first(index) = source(index)
        val sourceIndex = index + length
        if sourceIndex < source.length then second(index) = source(sourceIndex)
        parity(index) = (first(index) ^ second(index)).toByte
        index += 1
      Chunk(first, second, parity).zipWithIndex.map { case (bytes, shardIndex) =>
        val refined =
          ErasureFragmentBytes.fromChunk(Chunk.fromArray(bytes)).fold(message => throw new IllegalStateException(message), identity)
        ErasureFragment(shardIndex, refined)
      }
    }

  def decode(key: BinaryKey.Block, available: Map[Int, ErasureFragment]): Task[CanonicalBlock] =
    for
      _      <- ZIO.fail(ErasureBlockStore.NotEnoughShards(key, available.size, Map.empty)).when(available.size < 2)
      length <- ZIO.fromEither(fragmentLength(key.bits.size)).mapError(new IllegalArgumentException(_))
      first  <- shard(0, length, available)
      second <- shard(1, length, available)
      output <- ZIO.attempt {
                  val bytes = Array.ofDim[Byte](key.bits.size.toInt)
                  var index = 0
                  while index < bytes.length do
                    bytes(index) = if index < first.length then first(index) else second(index - first.length)
                    index += 1
                  bytes
                }
      _      <- validate(key, output)
      bytes   = Chunk.fromArray(output)
      block  <- ZIO.fromEither(CanonicalBlock.make(key, bytes, BinaryAttributes.empty)).mapError(new IllegalArgumentException(_))
    yield block

  private def shard(index: Int, length: Int, available: Map[Int, ErasureFragment]): Task[Chunk[Byte]] =
    available.get(index) match
      case Some(fragment) => ZIO.succeed(fragment.chunk)
      case None           =>
        val otherIndex = if index == 0 then 1 else 0
        for
          other  <- ZIO.fromOption(available.get(otherIndex)).orElseFail(new IllegalStateException("missing data shard"))
          parity <- ZIO.fromOption(available.get(2)).orElseFail(new IllegalStateException("missing parity shard"))
          result <- ZIO.attempt {
                      val output = Array.ofDim[Byte](length)
                      val a      = other.chunk
                      val p      = parity.chunk
                      var i      = 0
                      while i < length do
                        output(i) = (a(i) ^ p(i)).toByte
                        i += 1
                      Chunk.fromArray(output)
                    }
        yield result

  private def validate(key: BinaryKey.Block, bytes: Array[Byte]): Task[Unit] =
    for
      _      <- ZIO
                  .fail(new IllegalStateException(s"Reconstructed length mismatch for ${key.bits.render}"))
                  .unless(bytes.length.toLong == key.bits.size)
      hasher <- ZIO.fromEither(Hasher.hasher(key.bits.algo)).mapError(new IllegalArgumentException(_))
      _      <- ZIO.attempt(hasher.update(bytes))
      digest <- ZIO.fromEither(hasher.digest).mapError(new IllegalArgumentException(_))
      _      <- ZIO.fail(new IllegalStateException(s"Reconstructed digest mismatch for ${key.bits.render}")).unless(digest == key.bits.digest)
    yield ()
