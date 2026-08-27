package graviton.protocol.grpc

import com.google.protobuf.ByteString
import graviton.core.keys.BinaryKey
import graviton.core.types.FileSize
import graviton.runtime.model.{BlobBlockDescription, BlobListing, BlobStat}
import graviton.shared.MediaTypeText
import io.grpc.*
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.all.*
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder
import io.graviton.blobstore.v1.admin_service.{HealthRequest, HealthResponse}
import io.graviton.blobstore.v1.admin_service.ZioAdminService.AdminServiceClient
import io.graviton.blobstore.v1.blob_service.*
import io.graviton.blobstore.v1.blob_service.ZioBlobService.BlobServiceClient
import scalapb.zio_grpc.ZManagedChannel
import zio.*
import zio.blocks.mediatype.MediaType
import zio.stream.*

final class GravitonGrpcClient private (stub: BlobServiceClient, admin: AdminServiceClient):

  import GravitonGrpcClient.*

  def put(
    data: ZStream[Any, Throwable, Byte],
    contentType: MediaType,
    expectedSize: Option[FileSize] = None,
  ): IO[StatusException, PutResult] =
    ZIO
      .fromEither(MediaTypeText.renderEither(contentType))
      .mapError(message => Status.INVALID_ARGUMENT.withDescription(message).asException())
      .flatMap { renderedType =>
        val metadata = PutBlobRequest(
          PutBlobRequest.Kind.Metadata(
            PutBlobMetadata(expectedSize = expectedSize.map(_.value), contentType = renderedType)
          )
        )
        val chunks   = data
          .rechunk(GrpcProtocol.MaxChunkBytes)
          .chunks
          .map(chunk => PutBlobRequest(PutBlobRequest.Kind.Data(ByteString.copyFrom(chunk.toArray))))
          .mapError(error => Status.fromThrowable(error).asException())

        stub.putBlob(ZStream.succeed(metadata) ++ chunks).flatMap { response =>
          for
            key          <- parseKey(response.key.map(_.value).getOrElse(""))
            size         <- ZIO
                              .fromEither(FileSize.either(response.size))
                              .mapError(message => Status.INTERNAL.withDescription(message).asException())
            returnedType <- ZIO
                              .fromEither(MediaTypeText.parse(response.contentType))
                              .mapError(message => Status.DATA_LOSS.withDescription(message).asException())
          yield PutResult(key, size, returnedType)
        }
      }

  def get(key: BinaryKey.Blob): ZStream[Any, StatusException, Byte] =
    stub
      .getBlob(GetBlobRequest(key = Some(BlobKey(GrpcProtocol.render(key)))))
      .mapAccumZIO(0L) { case (expectedOffset, frame) =>
        if frame.offset != expectedOffset then
          ZIO.fail(
            Status.DATA_LOSS
              .withDescription(s"non-contiguous download frame: expected offset $expectedOffset, received ${frame.offset}")
              .asException()
          )
        else
          ZIO
            .fromEither(GrpcProtocol.DataChunk.fromByteString(frame.data))
            .mapError(message => Status.RESOURCE_EXHAUSTED.withDescription(message).asException())
            .map(bytes => (expectedOffset + bytes.length.toLong, bytes))
      }
      .flattenChunks

  def stat(key: BinaryKey.Blob): IO[StatusException, BlobStat] =
    stub
      .statBlob(BlobKey(GrpcProtocol.render(key)))
      .flatMap { response =>
        ZIO
          .fromEither(FileSize.either(response.size))
          .mapError(message => Status.DATA_LOSS.withDescription(message).asException())
          .map(size => BlobStat(size, key.bits.digest, java.time.Instant.ofEpochMilli(response.lastModifiedEpochMillis)))
      }

  def list: ZStream[Any, StatusException, BlobListing] =
    stub.listBlobs(ListBlobsRequest()).mapZIO { summary =>
      for
        key  <- parseKey(summary.key.map(_.value).getOrElse(""))
        size <- ZIO
                  .fromEither(FileSize.either(summary.size))
                  .mapError(message => Status.DATA_LOSS.withDescription(message).asException())
      yield BlobListing(
        key,
        BlobStat(size, key.bits.digest, java.time.Instant.ofEpochMilli(summary.lastModifiedEpochMillis)),
        summary.blockCount,
      )
    }

  def inspect(key: BinaryKey.Blob): ZStream[Any, StatusException, BlobBlockDescription] =
    stub.inspectBlob(InspectBlobRequest(key = Some(BlobKey(GrpcProtocol.render(key))))).mapZIO { block =>
      for
        bits     <- ZIO
                      .fromEither(graviton.core.keys.KeyBits.fromString(block.key))
                      .mapError(message => Status.DATA_LOSS.withDescription(message).asException())
        blockKey <- ZIO
                      .fromEither(BinaryKey.block(bits))
                      .mapError(message => Status.DATA_LOSS.withDescription(message).asException())
      yield BlobBlockDescription(block.index, blockKey, block.offset, block.size)
    }

  def delete(key: BinaryKey.Blob): IO[StatusException, Unit] =
    stub.deleteBlob(DeleteBlobRequest(key = Some(BlobKey(GrpcProtocol.render(key))))).unit

  def health: IO[StatusException, Unit] =
    admin.health(HealthRequest()).flatMap { response =>
      if response.status == HealthResponse.ServingStatus.SERVING then ZIO.unit
      else
        ZIO.fail(
          Status.UNAVAILABLE
            .withDescription("storage backend is not serving")
            .asException()
        )
    }

  private def parseKey(value: String): IO[StatusException, BinaryKey.Blob] =
    ZIO
      .fromEither(GrpcProtocol.parseBlobKey(value))
      .mapError(message => Status.DATA_LOSS.withDescription(message).asException())

object GravitonGrpcClient:
  final case class PutResult(key: BinaryKey.Blob, size: FileSize, contentType: MediaType)

  type BearerToken = BearerToken.T
  object BearerToken extends RefinedType[String, MinLength[1] & MaxLength[8192]]

  def scoped(host: String, port: Int, bearerToken: Option[BearerToken] = None): ZIO[Scope, Throwable, GravitonGrpcClient] =
    val builder = NettyChannelBuilder
      .forAddress(host, port)
      .usePlaintext()
      .maxInboundMessageSize(GrpcProtocol.MaxInboundMessageBytes)
    bearerToken.foreach(token => builder.intercept(new BearerTokenInterceptor(token)))
    val channel = ZManagedChannel(
      builder
    )
    for
      blob  <- BlobServiceClient.scoped(channel)
      admin <- AdminServiceClient.scoped(channel)
    yield new GravitonGrpcClient(blob, admin)

  private final class BearerTokenInterceptor(token: BearerToken) extends ClientInterceptor:
    private val authorization = Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER)

    override def interceptCall[ReqT, RespT](
      method: MethodDescriptor[ReqT, RespT],
      callOptions: CallOptions,
      next: Channel,
    ): ClientCall[ReqT, RespT] =
      new ForwardingClientCall.SimpleForwardingClientCall[ReqT, RespT](next.newCall(method, callOptions)):
        override def start(responseListener: ClientCall.Listener[RespT], headers: Metadata): Unit =
          headers.put(authorization, s"Bearer ${token.value}")
          super.start(responseListener, headers)
