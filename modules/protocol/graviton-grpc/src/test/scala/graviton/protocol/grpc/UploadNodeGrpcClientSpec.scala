package graviton.protocol.grpc

import UploadNodeGrpcClient.*
import graviton.runtime.upload.RegisterUploadRequest as DomainRegisterUploadRequest
import graviton.runtime.upload.UploadedPart
import io.grpc.Status
import java.nio.charset.StandardCharsets
import zio.*
import zio.Chunk
import zio.stream.ZStream
import zio.test.*

object UploadNodeGrpcClientSpec extends ZIOSpecDefault:

  override def spec: Spec[TestEnvironment, Any] =
    suite("UploadNodeGrpcClient")(
      test("uploadChunks splits stream into sequential chunks and validates acknowledgements") {
        for {
          recorded <- Ref.make(List.empty[UploadChunk])
          service   = new UploadServiceClient:
                        override def registerUpload(request: RegisterUploadRequest): IO[Status, RegisterUploadResponse] =
                          ZIO.dieMessage("unexpected register call")

                        override def uploadParts(request: ZStream[Any, Status, UploadChunk]): ZStream[Any, Status, UploadAck] =
                          ZStream.unwrap {
                            request.runCollect.flatMap { chunks =>
                              val asList = chunks.toList
                              recorded.set(asList) *>
                                ZIO.succeed(
                                  ZStream.fromIterable(
                                    asList.zipWithIndex
                                      .foldLeft((0L, List.empty[UploadAck])) { case ((total, acc), (chunk, index)) =>
                                        val nextTotal = total + chunk.data.size
                                        val ack       = UploadAck(chunk.uploadId, index + 1, nextTotal)
                                        (nextTotal, acc :+ ack)
                                      }
                                      ._2
                                  )
                                )
                            }
                          }

                        override def completeUpload(request: CompleteUploadRequest): IO[Status, CompleteUploadResponse] =
                          ZIO.dieMessage("unexpected complete call")

          client    = new UploadNodeGrpcClient(service)
          data      = ZStream.fromChunks(Chunk.fromArray("abcdefghij".getBytes(StandardCharsets.UTF_8)))
          progress <- client.uploadChunks("upload-1", data, Some(4), Map.empty[Int, String])
          chunks   <- recorded.get
        } yield assertTrue(
          chunks.map(_.sequenceNumber) == List(1, 2, 3),
          chunks.map(_.offset) == List(0L, 4L, 8L),
          chunks.lastOption.exists(_.last),
          progress.sequence == 3,
          progress.bytes == 10L,
        )
      },
      test("completeUpload renders domain parts to gRPC request") {
        for {
          captured <- Ref.make[Option[CompleteUploadRequest]](None)
          service   = new UploadServiceClient:
                        override def registerUpload(request: RegisterUploadRequest): IO[Status, RegisterUploadResponse] =
                          ZIO.succeed(RegisterUploadResponse("upload-1", 1024, 32, None))

                        override def uploadParts(request: ZStream[Any, Status, UploadChunk]): ZStream[Any, Status, UploadAck] =
                          ZStream.empty

                        override def completeUpload(request: CompleteUploadRequest): IO[Status, CompleteUploadResponse] =
                          captured.set(Some(request)) *> ZIO.succeed(CompleteUploadResponse("doc-99", Map("zone" -> "eu")))

          client  = new UploadNodeGrpcClient(service)
          parts   = Chunk(UploadedPart(1, 0L, 20L, Some("chk-1")))
          result <- client.completeUpload("upload-1", parts, Some("final"))
          req    <- captured.get.someOrFailException
        } yield assertTrue(
          req.uploadId == "upload-1",
          req.expectedChecksum.contains("final"),
          req.parts.headOption.exists(_.checksum.contains("chk-1")),
          result.documentId == "doc-99",
        )
      },
      test("non monotonic acknowledgements fail fast") {
        val flaky = new UploadServiceClient:
          override def registerUpload(request: RegisterUploadRequest): IO[Status, RegisterUploadResponse] =
            ZIO.succeed(RegisterUploadResponse("upload-1", 4, 32, None))

          override def uploadParts(request: ZStream[Any, Status, UploadChunk]): ZStream[Any, Status, UploadAck] =
            ZStream(UploadAck("upload-1", 1, 4L), UploadAck("upload-1", 1, 4L))

          override def completeUpload(request: CompleteUploadRequest): IO[Status, CompleteUploadResponse] =
            ZIO.dieMessage("not expected")

        val client = new UploadNodeGrpcClient(flaky)
        val data   = ZStream.fromChunks(Chunk.fromArray("abcd".getBytes(StandardCharsets.UTF_8)))
        client.uploadChunks("upload-1", data, Some(4), Map.empty[Int, String]).either.map { result =>
          assertTrue(result.isLeft)
        }
      },
      test("registerUpload maps RPC failures into domain error") {
        val failing = new UploadServiceClient:
          override def registerUpload(request: RegisterUploadRequest): IO[Status, RegisterUploadResponse] =
            ZIO.fail(Status.INVALID_ARGUMENT.withDescription("bad request"))

          override def uploadParts(request: ZStream[Any, Status, UploadChunk]): ZStream[Any, Status, UploadAck] =
            ZStream.empty

          override def completeUpload(request: CompleteUploadRequest): IO[Status, CompleteUploadResponse] =
            ZIO.dieMessage("unused")

        val client  = new UploadNodeGrpcClient(failing)
        val request = DomainRegisterUploadRequest.empty
        client.registerUpload(request).either.map { result =>
          assertTrue(result.swap.exists(_.message.contains("bad request")))
        }
      },
    )
