package graviton.backend.laws

import graviton.core.types.{BlobOffset, FileSize}
import graviton.runtime.model.{BlobWritePlan, InventoryPageSize}
import graviton.runtime.stores.{BlobStore, StoreError, StoreOperation}
import graviton.streams.BoundedByteStream
import zio.*
import zio.stream.ZStream
import zio.test.*

/** Reusable behavioral contract for every logical blob backend. */
object BlobStoreLaws:
  private val FirstPayload  = Chunk.fromArray(Array.tabulate[Byte](256 * 1024)(index => (index % 251).toByte))
  private val SecondPayload = Chunk.fromArray(Array.tabulate[Byte](96 * 1024)(index => ((index * 7) % 251).toByte))
  private val ThirdPayload  = Chunk.fromArray(Array.tabulate[Byte](64 * 1024)(index => ((index * 13) % 251).toByte))

  /** `acquire` must return an isolated empty store and retain its resources in the supplied scope. */
  def suite(backendName: String)(acquire: ZIO[Scope, StoreError, BlobStore]): Spec[TestEnvironment, StoreError] =
    zio.test.suite(s"$backendName BlobStore laws")(
      zio.test.test("streams a round trip without changing content identity") {
        withStore(acquire) { store =>
          for
            written <- upload(store, FirstPayload)
            read    <- collectFixture(store.get(written.key))
            stat    <- store.stat(written.key)
          yield assertTrue(read == FirstPayload, stat.exists(_.size.value == FirstPayload.length.toLong))
        }
      },
      zio.test.test("makes duplicate writes idempotent and lists one logical blob") {
        withStore(acquire) { store =>
          for
            first  <- upload(store, FirstPayload)
            second <- upload(store, FirstPayload)
            count  <- store.streamInventory.runFold(0)((current, listing) => current + (if listing.key == first.key then 1 else 0))
          yield assertTrue(first.key == second.key, count == 1)
        }
      },
      zio.test.test("serves exact bounded byte ranges") {
        withStore(acquire) { store =>
          for
            written <- upload(store, FirstPayload)
            start   <- ZIO.fromEither(BlobOffset.either(8192L)).mapError(lawInput)
            length  <- ZIO.fromEither(FileSize.either(32768L)).mapError(lawInput)
            range   <- collectFixture(store.getRange(written.key, start, length))
          yield assertTrue(range == FirstPayload.slice(start.value.toInt, start.value.toInt + length.value.toInt))
        }
      },
      zio.test.test("uses opaque bounded cursor pages without duplicates or omissions") {
        withStore(acquire) { store =>
          for
            _      <- ZIO.foreachDiscard(Chunk(FirstPayload, SecondPayload, ThirdPayload))(upload(store, _))
            size   <- ZIO.fromEither(InventoryPageSize.either(2)).mapError(lawInput)
            first  <- store.inventoryPage(None, size)
            second <- ZIO.foreach(first.next)(cursor => store.inventoryPage(Some(cursor), size))
            all     = first.items ++ second.fold(Chunk.empty)(_.items)
          yield assertTrue(
            first.items.length == 2,
            first.next.nonEmpty,
            second.exists(_.next.isEmpty),
            all.length == 3,
            all.map(_.key).distinct.length == 3,
          )
        }
      },
      zio.test.test("deletes metadata and makes the blob unreachable") {
        withStore(acquire) { store =>
          for
            written <- upload(store, SecondPayload)
            _       <- store.delete(written.key)
            stat    <- store.stat(written.key)
            read    <- store.get(written.key).runDrain.either
          yield assertTrue(stat.isEmpty, read.isLeft)
        }
      },
      zio.test.test("interruption never publishes a partial logical blob") {
        withStore(acquire) { store =>
          for
            before  <- store.streamInventory.runHead
            reached <- Promise.make[Nothing, Unit]
            release <- Promise.make[Nothing, Unit]
            source   = ZStream.fromChunk(FirstPayload.take(64 * 1024)) ++
                         ZStream.fromZIO(reached.succeed(()) *> release.await).drain ++
                         ZStream.fromChunk(FirstPayload.drop(64 * 1024))
            fiber   <- source.run(store.put(BlobWritePlan())).fork
            _       <- reached.await
            _       <- fiber.interrupt
            after   <- store.streamInventory.runHead
          yield assertTrue(before.isEmpty, after.isEmpty)
        }
      },
    ) @@ TestAspect.sequential @@ TestAspect.withLiveClock

  private def withStore[A](acquire: ZIO[Scope, StoreError, BlobStore])(use: BlobStore => IO[StoreError, A]): IO[StoreError, A] =
    ZIO.scoped(acquire.flatMap(use))

  private def upload(store: BlobStore, bytes: Chunk[Byte]) =
    ZStream.fromChunk(bytes).run(store.put())

  private def collectFixture(stream: ZStream[Any, StoreError, Byte]) =
    BoundedByteStream.collectInMemory(stream).mapError {
      case error: StoreError              => error
      case error: BoundedByteStream.Error => StoreError.CorruptData(StoreOperation.GetBlob, error.getMessage, error)
    }

  private def lawInput(message: String): StoreError =
    StoreError.InvalidInput(StoreOperation.PutBlob, s"invalid backend law fixture: $message")
