package graviton.protocol.grpc

import com.google.protobuf.ByteString
import graviton.core.attributes.BinaryAttributes
import graviton.core.types.{FileSize, Mime}
import graviton.runtime.model.BlobWritePlan
import graviton.runtime.stores.BlobStore
import io.grpc.{Status, StatusException}
import io.graviton.blobstore.v1.blob_service.*
import io.graviton.blobstore.v1.blob_service.ZioBlobService.BlobService
import zio.*
import zio.blocks.mediatype.MediaType
import zio.stream.*

final class BlobServiceImpl(blobStore: BlobStore) extends BlobService:

  override def putBlob(request: Stream[StatusException, PutBlobRequest]): IO[StatusException, PutBlobResponse] =
    ZIO
      .scoped {
        request.peel(ZSink.head).flatMap { case (first, remaining) =>
          for
            metadata <- ZIO.fromOption(first.flatMap(_.kind.metadata)).orElseFail(invalid("first upload frame must be metadata"))
            plan     <- writePlan(metadata)
            bytes     = remaining.mapZIO(frameBytes).flattenChunks
            result   <- bytes.run(blobStore.put(plan)).mapError(toStatus)
            _        <- metadata.expectedSize match
                          case Some(expected) if expected != result.key.bits.size =>
                            blobStore.delete(result.key).ignore *>
                              ZIO.fail(invalid(s"expected $expected bytes but received ${result.key.bits.size}"))
                          case _                                                  => ZIO.unit
          yield PutBlobResponse(
            key = Some(BlobKey(GrpcProtocol.render(result.key))),
            size = result.key.bits.size,
            contentType = metadata.contentType,
          )
        }
      }
      .mapError(toStatus)

  override def getBlob(request: GetBlobRequest): Stream[StatusException, BlobChunk] =
    ZStream
      .fromZIO(parseKey(request.key.map(_.value).getOrElse("")))
      .flatMap { key =>
        blobStore
          .get(key)
          .rechunk(GrpcProtocol.MaxChunkBytes)
          .chunks
          .mapAccum(0L) { case (offset, bytes) =>
            (offset + bytes.length.toLong, BlobChunk(ByteString.copyFrom(bytes.toArray), offset))
          }
          .mapError(toStatus)
      }

  override def statBlob(request: BlobKey): IO[StatusException, StatBlobResponse] =
    for
      key  <- parseKey(request.value)
      stat <- blobStore.stat(key).mapError(toStatus).someOrFail(notFound(key.bits.render))
    yield StatBlobResponse(
      size = stat.size.value,
      digest = stat.digest.hex.value,
      lastModifiedEpochMillis = stat.lastModified.toEpochMilli,
    )

  override def listBlobs(request: ListBlobsRequest): Stream[StatusException, BlobSummary] =
    ZStream
      .fromZIO(blobStore.list.mapError(toStatus))
      .flatMap(ZStream.fromChunk)
      .map { listing =>
        BlobSummary(
          key = Some(BlobKey(GrpcProtocol.render(listing.key))),
          size = listing.stat.size.value,
          digest = listing.stat.digest.hex.value,
          lastModifiedEpochMillis = listing.stat.lastModified.toEpochMilli,
          blockCount = listing.blockCount,
        )
      }

  override def inspectBlob(request: InspectBlobRequest): Stream[StatusException, BlobBlock] =
    ZStream
      .fromZIO(parseKey(request.key.map(_.value).getOrElse("")))
      .flatMap { key =>
        ZStream
          .fromZIO(blobStore.inspect(key).mapError(toStatus).someOrFail(notFound(key.bits.render)))
          .flatMap(description => ZStream.fromChunk(description.blocks))
          .map(block => BlobBlock(block.index, GrpcProtocol.render(block.key), block.offset, block.size))
      }

  override def deleteBlob(request: DeleteBlobRequest): IO[StatusException, DeleteBlobResponse] =
    for
      key <- parseKey(request.key.map(_.value).getOrElse(""))
      _   <- blobStore.delete(key).mapError(toStatus)
    yield DeleteBlobResponse()

  private def writePlan(metadata: PutBlobMetadata): IO[StatusException, BlobWritePlan] =
    for
      mediaType <- ZIO.fromEither(MediaType.parse(metadata.contentType)).mapError(invalid)
      mime      <- ZIO.fromEither(Mime.either(mediaType.fullType)).mapError(invalid)
      expected  <- ZIO.foreach(metadata.expectedSize)(value => ZIO.fromEither(FileSize.either(value)).mapError(invalid))
      attributes = expected
                     .fold(BinaryAttributes.empty)(BinaryAttributes.empty.advertiseSize)
                     .advertiseMime(mime)
    yield BlobWritePlan(attributes = attributes)

  private def frameBytes(frame: PutBlobRequest): IO[StatusException, GrpcProtocol.DataChunk] =
    frame.kind match
      case PutBlobRequest.Kind.Data(value) => ZIO.fromEither(GrpcProtocol.DataChunk.fromByteString(value)).mapError(invalid)
      case PutBlobRequest.Kind.Metadata(_) => ZIO.fail(invalid("upload metadata may appear only once"))
      case PutBlobRequest.Kind.Empty       => ZIO.fail(invalid("empty upload frame"))

  private def parseKey(value: String): IO[StatusException, graviton.core.keys.BinaryKey.Blob] =
    ZIO.fromEither(GrpcProtocol.parseBlobKey(value)).mapError(invalid)

  private def invalid(message: String): StatusException =
    Status.INVALID_ARGUMENT.withDescription(message).asException()

  private def notFound(key: String): StatusException =
    Status.NOT_FOUND.withDescription(s"blob '$key' was not found").asException()

  private def toStatus(error: Throwable): StatusException =
    error match
      case status: StatusException     => status
      case _: NoSuchElementException   => Status.NOT_FOUND.withDescription("blob was not found").asException()
      case _: IllegalArgumentException => Status.INVALID_ARGUMENT.withDescription(safeMessage(error)).asException()
      case _                           => Status.INTERNAL.withDescription("storage operation failed").withCause(error).asException()

  private def safeMessage(error: Throwable): String =
    Option(error.getMessage).filter(_.nonEmpty).getOrElse("invalid request")
