package graviton.backend.laws

import graviton.runtime.model.BlobWritePlan
import graviton.runtime.stores.{BlobStore, StoreBackend, StoreError, StoreOperation}
import graviton.streams.{BoundedByteStream, Chunker}
import graviton.core.types.UploadChunkSize
import zio.*
import zio.stream.ZStream
import zio.test.*

/** Backend-owned restart fixture consumed by the reusable crash laws. */
trait CrashStoreFixture:
  def current: UIO[BlobStore]
  def restart: IO[StoreError, Unit]
  def controller: FaultController

trait CrashBackend:
  /** Return an isolated empty repository with the supplied deterministic plan. */
  def open(plan: FaultPlan): ZIO[Scope, StoreError, CrashStoreFixture]

/** Crash-consistency contract for logical CAS publication and restart. */
object CrashConsistencyLaws:
  private val Payload = Chunk.fromArray(Array.tabulate[Byte](512 * 1024)(index => ((index * 31) % 251).toByte))
  private val Chunk64 = UploadChunkSize.applyUnsafe(64 * 1024)

  def suite(backendName: String)(backend: CrashBackend): Spec[TestEnvironment, StoreError] =
    zio.test.suite(s"$backendName crash consistency")(
      zio.test.test("acknowledged bytes survive restart") {
        withFixture(backend, FaultPlan.empty) { fixture =>
          for
            store     <- fixture.current
            written   <- upload(store)
            _         <- fixture.restart
            restarted <- fixture.current
            read      <- collectFixture(restarted.get(written.key))
          yield assertTrue(read == Payload)
        }
      },
      zio.test.test("failure before manifest publication leaves no logical blob") {
        val plan = failPlan(StoreOperation.PutManifest, FaultPhase.Before)
        withValidatedFixture(backend, plan) { fixture =>
          for
            store     <- fixture.current
            failed    <- upload(store).either
            _         <- fixture.restart
            restarted <- fixture.current
            count     <- restarted.streamInventory.runFold(0)((current, _) => current + 1)
            events    <- fixture.controller.events
          yield assertTrue(
            failed.isLeft,
            count == 0,
            events.exists(event => event.point == FaultPoint(StoreOperation.PutManifest, FaultPhase.Before) && event.action.nonEmpty),
          )
        }
      },
      zio.test.test("lost acknowledgement after manifest publication retries idempotently") {
        val plan = failPlan(StoreOperation.PutManifest, FaultPhase.After)
        withValidatedFixture(backend, plan) { fixture =>
          for
            store     <- fixture.current
            failed    <- upload(store).either
            _         <- fixture.restart
            restarted <- fixture.current
            retried   <- upload(restarted)
            count     <- restarted.streamInventory.runFold(0)((current, listing) => current + (if listing.key == retried.key then 1 else 0))
            read      <- collectFixture(restarted.get(retried.key))
          yield assertTrue(
            failed.isLeft,
            retried.stats.duplicateBlocks == retried.stats.blockCount,
            count == 1,
            read == Payload,
          )
        }
      },
      zio.test.test("interrupted source never publishes after restart") {
        withFixture(backend, FaultPlan.empty) { fixture =>
          for
            store     <- fixture.current
            reached   <- Promise.make[Nothing, Unit]
            release   <- Promise.make[Nothing, Unit]
            source     = ZStream.fromChunk(Payload.take(64 * 1024)) ++
                           ZStream.fromZIO(reached.succeed(()) *> release.await).drain ++
                           ZStream.fromChunk(Payload.drop(64 * 1024))
            fiber     <- Chunker.locally(Chunker.fixed(Chunk64))(source.run(store.put(BlobWritePlan()))).fork
            _         <- reached.await
            _         <- fiber.interrupt
            _         <- fixture.restart
            restarted <- fixture.current
            count     <- restarted.streamInventory.runFold(0)((current, _) => current + 1)
          yield assertTrue(count == 0)
        }
      },
    ) @@ TestAspect.sequential

  private def withFixture[A](
    backend: CrashBackend,
    plan: FaultPlan,
  )(use: CrashStoreFixture => IO[StoreError, A]): IO[StoreError, A] =
    ZIO.scoped(backend.open(plan).flatMap(use))

  private def withValidatedFixture[A](
    backend: CrashBackend,
    plan: Either[FaultPlanError, FaultPlan],
  )(use: CrashStoreFixture => IO[StoreError, A]): IO[StoreError, A] =
    ZIO
      .fromEither(plan)
      .mapError(error => lawInput(error.getMessage))
      .flatMap(validated => withFixture(backend, validated)(use))

  private def upload(store: BlobStore) =
    Chunker.locally(Chunker.fixed(Chunk64))(ZStream.fromChunk(Payload).run(store.put()))

  private def collectFixture(stream: ZStream[Any, StoreError, Byte]) =
    BoundedByteStream.collectInMemory(stream).mapError {
      case error: StoreError              => error
      case error: BoundedByteStream.Error => StoreError.CorruptData(StoreOperation.GetBlob, error.getMessage, error)
    }

  private def failPlan(operation: StoreOperation, phase: FaultPhase): Either[FaultPlanError, FaultPlan] =
    FaultPlan.single(
      FaultRule(
        FaultPoint(operation, phase),
        FaultOccurrence.First,
        FaultAction.Fail(InjectedStoreFailure.Unavailable(StoreBackend.Runtime)),
      )
    )

  private def lawInput(message: String): StoreError =
    StoreError.InvalidInput(StoreOperation.HealthCheck, s"invalid crash-law fixture: $message")
