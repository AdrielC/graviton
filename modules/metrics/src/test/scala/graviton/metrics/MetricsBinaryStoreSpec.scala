package graviton.metrics

import graviton.{BinaryId, BinaryStore, ByteRange}
import zio.*
import zio.stream.*
import zio.test.*

object MetricsBinaryStoreSpec extends ZIOSpecDefault:
  private val stub = new BinaryStore:
    def put: ZSink[Any, Throwable, Byte, Nothing, BinaryId] =
      ZSink.succeed(BinaryId("id"))
    def get(
      id: BinaryId,
      range: Option[ByteRange],
    ): IO[Throwable, Option[graviton.Bytes]] =
      ZIO.succeed(None)
    def delete(id: BinaryId): IO[Throwable, Boolean] =
      ZIO.succeed(true)
    def exists(id: BinaryId): IO[Throwable, Boolean] =
      ZIO.succeed(true)

  def spec = suite("MetricsBinaryStore")(
    test("records get count") {
      val store = MetricsBinaryStore(stub)
      for
        before <- Metrics.getCount.value.map(_.count)
        _      <- store.get(BinaryId("a"), None)
        after  <- Metrics.getCount.value.map(_.count)
      yield assertTrue(after == before + 1)
    },
    test("records delete count") {
      val store = MetricsBinaryStore(stub)
      for
        before <- Metrics.deleteCount.value.map(_.count)
        _      <- store.delete(BinaryId("a"))
        after  <- Metrics.deleteCount.value.map(_.count)
      yield assertTrue(after == before + 1)
    },
  )
