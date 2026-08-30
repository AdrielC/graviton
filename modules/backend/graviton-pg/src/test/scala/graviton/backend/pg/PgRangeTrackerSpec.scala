package graviton.backend.pg

import graviton.core.locator.BlobLocator
import graviton.core.ranges.{RangeSet, Span}
import graviton.core.types.BlobOffset
import graviton.runtime.kv.{KeyValueStore, KvKey, KvValue}
import graviton.runtime.stores.{StoreBackend, StoreError, StoreOperation}
import zio.*
import zio.test.*

object PgRangeTrackerSpec extends ZIOSpecDefault:

  override def spec: Spec[TestEnvironment, Any] =
    suite("PgRangeTracker")(
      test("concurrent merges persist one complete range set") {
        val locator = BlobLocator.from("pg", "ranges", "concurrent").toOption.get
        val spans   = Chunk.fromIterable(0 until 64).map { index =>
          val offset = BlobOffset.unsafe(index.toLong * 2L)
          Span.make(offset, offset).toOption.get
        }

        for
          store    <- TestKeyValueStore.make
          tracker  <- PgRangeTracker.make(store)
          _        <- ZIO.foreachParDiscard(spans)(tracker.merge(locator, _))
          reloaded <- PgRangeTracker.make(store).flatMap(_.current(locator))
        yield assertTrue(reloaded == RangeSet.fromSpans(spans))
      },
      test("failed persistence does not publish an uncommitted cache value") {
        val locator = BlobLocator.from("pg", "ranges", "failed-write").toOption.get
        val offset  = BlobOffset.unsafe(42L)
        val span    = Span.make(offset, offset).toOption.get

        for
          store   <- TestKeyValueStore.make
          tracker <- PgRangeTracker.make(store)
          _       <- store.failNextPut
          exit    <- tracker.merge(locator, span).exit
          current <- tracker.current(locator)
        yield assertTrue(exit.isFailure, current.isEmpty)
      },
    )

  private final class TestKeyValueStore(
    values: Ref[Map[KvKey, KvValue]],
    failNext: Ref[Boolean],
  ) extends KeyValueStore:

    override def put(key: KvKey, value: KvValue): IO[StoreError, Unit] =
      failNext.getAndSet(false).flatMap { fail =>
        if fail then
          ZIO.fail(
            StoreError.Unavailable(
              StoreOperation.PutKeyValue,
              StoreBackend.InMemory,
              new RuntimeException("intentional persistence failure"),
            )
          )
        else values.update(_.updated(key, value))
      }

    override def get(key: KvKey): IO[StoreError, Option[KvValue]] = values.get.map(_.get(key))

    override def delete(key: KvKey): IO[StoreError, Unit] = values.update(_ - key)

    def failNextPut: UIO[Unit] = failNext.set(true)

  private object TestKeyValueStore:
    def make: UIO[TestKeyValueStore] =
      for
        values   <- Ref.make(Map.empty[KvKey, KvValue])
        failNext <- Ref.make(false)
      yield new TestKeyValueStore(values, failNext)
