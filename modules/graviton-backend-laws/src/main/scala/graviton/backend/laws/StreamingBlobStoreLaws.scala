package graviton.backend.laws

import graviton.core.bytes.Hasher
import graviton.core.types.{FileSize, UploadChunkSize}
import graviton.runtime.model.BlobWritePlan
import graviton.runtime.stores.{BlobStore, StoreError, StoreOperation}
import graviton.runtime.upload.UploadByteStream
import graviton.streams.Chunker
import zio.*
import zio.stream.ZStream
import zio.test.*

/** Optional live-resource and heap observation supplied by a backend fixture. */
trait StreamingObservation:
  def openResources: UIO[Long]
  def retainedTransferBytes: UIO[Long]

object StreamingObservation:
  val unavailable: StreamingObservation = new StreamingObservation:
    override val openResources: UIO[Long]         = ZIO.succeed(0L)
    override val retainedTransferBytes: UIO[Long] = ZIO.succeed(0L)

/**
 * Published streaming contract for BlobStore implementations.
 *
 * The payload is generated one bounded chunk at a time and compared by a
 * streaming digest. No law materializes the complete fixture.
 */
object StreamingBlobStoreLaws:
  final case class Config(
    exercisedBytes: Long = 8L * 1024L * 1024L,
    chunkBytes: UploadChunkSize = UploadChunkSize.applyUnsafe(64 * 1024),
    maximumObservedTransferBytes: Long = 128L * 1024L * 1024L,
  ):
    require(exercisedBytes > chunkBytes.value.toLong, "streaming law fixture must span multiple chunks")
    require(maximumObservedTransferBytes > 0L, "streaming law heap ceiling must be positive")

  def suite(backendName: String)(
    acquire: ZIO[Scope, StoreError, BlobStore],
    observation: StreamingObservation = StreamingObservation.unavailable,
    config: Config = Config(),
  ): Spec[TestEnvironment, StoreError] =
    zio.test.suite(s"$backendName streaming laws")(
      zio.test.test("is lazy before the sink requests the first chunk") {
        withStore(acquire) { store =>
          for
            pulls  <- Ref.make(0L)
            source  = generated(config.exercisedBytes, config.chunkBytes.value, pulls)
            effect  = source.run(store.put(BlobWritePlan()))
            before <- pulls.get
            _      <- effect
          yield assertTrue(before == 0L)
        }
      },
      zio.test.test("pulls each bounded source chunk exactly once plus termination") {
        withStore(acquire) { store =>
          for
            pulls    <- Ref.make(0L)
            _        <- generated(config.exercisedBytes, config.chunkBytes.value, pulls).run(store.put())
            observed <- pulls.get
            expected  = divideRoundUp(config.exercisedBytes, config.chunkBytes.value.toLong) + 1L
          yield assertTrue(observed == expected)
        }
      },
      zio.test.test("checks a declared byte bound with at most N plus one bytes") {
        val maximum = FileSize.unsafe(4096L)
        for
          pulls  <- Ref.make(0L)
          source  = generated(maximum.value + config.chunkBytes.value.toLong, 1, pulls)
          failed <- UploadByteStream.enforceMaximumSize(source, maximum).runDrain.either
          count  <- pulls.get
        yield assertTrue(failed.isLeft, count == maximum.value + 1L)
      },
      zio.test.test("terminates an early download and releases fixture resources") {
        withStore(acquire) { store =>
          for
            pulls <- Ref.make(0L)
            saved <- generated(config.exercisedBytes, config.chunkBytes.value, pulls).run(store.put())
            _     <- store.get(saved.key).take(config.chunkBytes.value.toLong).runDrain
            open  <- observation.openResources
          yield assertTrue(open == 0L)
        }
      },
      zio.test.test("interruption leaves no published logical blob and releases resources") {
        withStore(acquire) { store =>
          for
            before  <- store.streamInventory.runFold(0L)((count, _) => count + 1L)
            reached <- Promise.make[Nothing, Unit]
            release <- Promise.make[Nothing, Unit]
            prefix   = ZStream.fromChunk(deterministicChunk(config.chunkBytes.value, 0L))
            source   = prefix ++ ZStream.fromZIO(reached.succeed(()) *> release.await).drain ++ prefix
            fiber   <- source.run(store.put()).fork
            _       <- reached.await
            _       <- fiber.interrupt
            after   <- store.streamInventory.runFold(0L)((count, _) => count + 1L)
            open    <- observation.openResources
          yield assertTrue(after == before, open == 0L)
        }
      },
      zio.test.test("round trips a multi-chunk source with a streaming digest") {
        withStore(acquire) { store =>
          for
            uploadHasher   <- ZIO.fromEither(Hasher.systemDefault).mapError(lawInput)
            downloadHasher <- ZIO.fromEither(Hasher.systemDefault).mapError(lawInput)
            pulls          <- Ref.make(0L)
            source          = generated(config.exercisedBytes, config.chunkBytes.value, pulls)
                                .mapChunksZIO(chunk => ZIO.attempt(uploadHasher.update(chunk)).as(chunk).mapError(lawFailure))
            saved          <- Chunker.locally(Chunker.fixed(config.chunkBytes))(source.run(store.put()))
            _              <-
              store.get(saved.key).mapChunksZIO(chunk => ZIO.attempt(downloadHasher.update(chunk)).as(chunk).mapError(lawFailure)).runDrain
            uploaded       <- ZIO.fromEither(uploadHasher.digest).mapError(lawInput)
            downloaded     <- ZIO.fromEither(downloadHasher.digest).mapError(lawInput)
            retained       <- observation.retainedTransferBytes
          yield assertTrue(uploaded == downloaded, retained <= config.maximumObservedTransferBytes)
        }
      },
      zio.test.test("supports the logical 1 TiB boundary without allocating it") {
        val oneTiB = 1024L * 1024L * 1024L * 1024L
        val size   = FileSize.either(oneTiB)
        val chunks = divideRoundUp(oneTiB, config.chunkBytes.value.toLong)
        ZIO.succeed(assertTrue(size.exists(_.value == oneTiB), chunks == 16777216L))
      },
    ) @@ TestAspect.sequential @@ TestAspect.withLiveClock

  private def withStore[A](acquire: ZIO[Scope, StoreError, BlobStore])(use: BlobStore => IO[StoreError, A]): IO[StoreError, A] =
    ZIO.scoped(acquire.flatMap(use))

  private def generated(totalBytes: Long, chunkBytes: Int, pulls: Ref[Long]): ZStream[Any, StoreError, Byte] =
    ZStream.unfoldChunkZIO(0L) { offset =>
      pulls.update(_ + 1L) *>
        (if offset >= totalBytes then ZIO.none
         else
           val length = math.min(chunkBytes.toLong, totalBytes - offset).toInt
           ZIO.some(deterministicChunk(length, offset) -> (offset + length.toLong)))
    }

  private def deterministicChunk(length: Int, offset: Long): Chunk[Byte] =
    Chunk.fromArray(Array.tabulate[Byte](length)(index => ((offset + index.toLong) % 251L).toByte))

  private def divideRoundUp(value: Long, divisor: Long): Long =
    value / divisor + (if value % divisor == 0L then 0L else 1L)

  private def lawInput(message: String): StoreError =
    StoreError.InvalidInput(StoreOperation.PutBlob, s"invalid streaming law fixture: $message")

  private def lawFailure(error: Throwable): StoreError =
    StoreError.fromThrowable(StoreOperation.PutBlob)(error)
