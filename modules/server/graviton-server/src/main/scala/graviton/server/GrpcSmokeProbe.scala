package graviton.server

import graviton.core.types.FileSize
import graviton.protocol.grpc.GravitonGrpcClient
import io.grpc.Status
import zio.*
import zio.blocks.mediatype.MediaType
import zio.stream.ZStream

/** Executes the packaged server's public gRPC blob lifecycle without materializing the payload. */
object GrpcSmokeProbe extends ZIOAppDefault:

  private val PayloadBytes = 3L * 1024 * 1024
  private val ContentType  = MediaType.unsafeFromString("application/octet-stream")

  override def run: Task[Unit] =
    ZIO
      .scoped {
        for
          host      <- env("GRAVITON_GRPC_HOST", "127.0.0.1")
          portText  <- env("GRAVITON_GRPC_PORT", "9090")
          port      <- ZIO
                         .fromOption(portText.toIntOption.filter(value => value > 0 && value <= 65535))
                         .orElseFail(new IllegalArgumentException(s"invalid GRAVITON_GRPC_PORT: $portText"))
          tokenText <- System.env("GRAVITON_GRPC_BEARER_TOKEN")
          token     <- ZIO.foreach(tokenText)(value => refineToken(value))
          client    <- GravitonGrpcClient.scoped(host, port, token)
          _         <- client.health
          _         <- Console.printLine("packaged gRPC health passed")
          source     = ZStream.iterate(0L)(_ + 1L).take(PayloadBytes).map(index => byteAt(index))
          written   <- client.put(source, ContentType, Some(FileSize.applyUnsafe(PayloadBytes)))
          _         <- Console.printLine("packaged gRPC upload passed")
          received  <- client
                         .get(written.key)
                         .zipWithIndex
                         .mapZIO { case (byte, index) =>
                           ZIO
                             .fail(new IllegalStateException(s"gRPC payload differs at offset $index"))
                             .unless(byte == byteAt(index))
                         }
                         .runCount
          _         <- Console.printLine("packaged gRPC download passed")
          stat      <- client.stat(written.key)
          listed    <- client.list.runFold(false)((found, item) => found || item.key == written.key)
          blocks    <- client.inspect(written.key).runCount
          _         <- Console.printLine("packaged gRPC metadata passed")
          _         <- ZIO
                         .fail(new IllegalStateException("gRPC lifecycle returned inconsistent metadata"))
                         .unless(
                           received == PayloadBytes &&
                             stat.size == written.size &&
                             listed &&
                             blocks > 0L
                         )
          _         <- client.delete(written.key)
          _         <- Console.printLine("packaged gRPC delete passed")
          missing   <- client.stat(written.key).exit
          _         <- ZIO
                         .fail(new IllegalStateException("deleted gRPC blob remained visible"))
                         .unless(
                           missing.causeOption
                             .flatMap(_.failureOption)
                             .exists(_.getStatus.getCode == Status.Code.NOT_FOUND)
                         )
          _         <- Console.printLine(s"packaged gRPC smoke passed: ${written.key.bits.render} bytes=$received")
        yield ()
      }
      .timeoutFail(new java.util.concurrent.TimeoutException("packaged gRPC smoke timed out"))(60.seconds)

  private def byteAt(index: Long): Byte = (index % 251L).toByte

  private def env(name: String, default: String): Task[String] =
    System.env(name).map(_.map(_.trim).filter(_.nonEmpty).getOrElse(default))

  private def refineToken(value: String): Task[GravitonGrpcClient.BearerToken] =
    ZIO
      .fromEither(GravitonGrpcClient.BearerToken.either(value))
      .mapError(message => new IllegalArgumentException(s"invalid GRAVITON_GRPC_BEARER_TOKEN: $message"))
