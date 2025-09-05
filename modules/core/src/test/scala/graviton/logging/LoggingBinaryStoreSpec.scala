package graviton.logging

import graviton.{BinaryId, BinaryStore, ByteRange}
import graviton.core.BinaryAttributes
import zio.*
import zio.stream.*
import zio.test.*
import zio.test.ZIOSpecDefault
import zio.test.ZTestLogger

object LoggingBinaryStoreSpec extends ZIOSpecDefault:
  private val dummyStore = new BinaryStore:
    def put(
        attrs: BinaryAttributes,
        chunkSize: Int
    ): ZSink[Any, Throwable, Byte, Nothing, BinaryId] =
      ZSink.fail(new NotImplementedError("unused"))
    def get(
        id: BinaryId,
        range: Option[ByteRange]
    ): IO[Throwable, Option[graviton.Bytes]] =
      ZIO.succeed(None)
    def delete(id: BinaryId): IO[Throwable, Boolean] = ZIO.succeed(true)
    def exists(id: BinaryId): IO[Throwable, Boolean] = ZIO.succeed(true)

  def spec =
    suite("LoggingBinaryStore")(
      test("logs once when applied multiple times") {
        val store = LoggingBinaryStore(LoggingBinaryStore(dummyStore))
        for
          _ <- store.exists(BinaryId("1"))
          logs <- ZTestLogger.logOutput
          start = logs.count(_.message() == "exists start")
          finish = logs.count(_.message() == "exists finish")
          ids = logs.map(_.annotations.get("correlation-id"))
        yield assertTrue(start == 1, finish == 1, ids.flatten.toSet.size == 1)
      }
    ).provideLayer(ZTestLogger.default)
