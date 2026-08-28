package graviton.protocol.grpc

import com.google.protobuf.ByteString
import graviton.core.attributes.BinaryAttributes
import graviton.core.types.FileSize
import graviton.runtime.model.BlobWritePlan
import graviton.runtime.stores.BlobStore
import graviton.runtime.upload.{UploadIngestor, UploadIntent}
import graviton.shared.MediaTypeText
import io.grpc.{Status, StatusException}
import io.graviton.blobstore.v1.blob_service.*
import io.graviton.blobstore.v1.blob_service.ZioBlobService.BlobService
import zio.*
import zio.blocks.mediatype.MediaType
import zio.stream.*

final class BlobServiceImpl(blobStore: BlobStore, uploadIngestor: UploadIngestor) extends BlobService:

  def this(blobStore: BlobStore) = this(blobStore, UploadIngestor.default(blobStore))

  override def putBlob(request: Stream[StatusException, PutBlobRequest]): IO[StatusException, PutBlobResponse] =
    ZIO
      .scoped {
        request.peel(ZSink.head).flatMap { case (first, remaining) =>
          for
            metadata <- ZIO.fromOption(first.flatMap(_.kind.metadata)).orElseFail(invalid("first upload frame must be metadata"))
            prepared <- prepareUpload(metadata)
            bytes     = remaining.mapZIO(frameBytes).flattenChunks
            result   <- uploadIngestor
                          .put(UploadIntent(prepared.mediaType, prepared.expectedSize), bytes, prepared.plan)
                          .mapError(toStatus)
          yield PutBlobResponse(
            key = Some(BlobKey(GrpcProtocol.render(result.stored.key))),
            size = result.stored.key.bits.size,
            contentType = MediaTypeText.render(result.effectiveMediaType),
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

  private final case class PreparedUpload(
    plan: BlobWritePlan,
    mediaType: MediaType,
    expectedSize: Option[FileSize],
  )

  private def prepareUpload(metadata: PutBlobMetadata): IO[StatusException, PreparedUpload] =
    for
      mediaType  <- ZIO.fromEither(MediaTypeText.parse(metadata.contentType)).mapError(invalid)
      expected   <- ZIO.foreach(metadata.expectedSize)(value => ZIO.fromEither(FileSize.either(value)).mapError(invalid))
      base        = expected.fold(BinaryAttributes.empty)(BinaryAttributes.empty.advertiseSize)
      attributes <- ZIO.fromEither(base.advertiseMediaType(mediaType)).mapError(invalid)
    yield PreparedUpload(BlobWritePlan(attributes = attributes), mediaType, expected)

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
      case status: StatusException                                       => status
      case UploadIngestor.Error.Source(status: StatusException)          => status
      case invalid: UploadIngestor.Error.InvalidInput                    => invalidStatus(invalid)
      case mismatch: UploadIngestor.Error.MediaTypeMismatch              => invalidStatus(mismatch)
      case ambiguous: UploadIngestor.Error.AmbiguousDetection            => invalidStatus(ambiguous)
      case validation: UploadIngestor.Error.Validation                   => invalidStatus(validation)
      case UploadIngestor.Error.Storage(cause: IllegalArgumentException) => invalidStatus(cause)
      case _: NoSuchElementException                                     => Status.NOT_FOUND.withDescription("blob was not found").asException()
      case _: IllegalArgumentException                                   => invalidStatus(error)
      case _                                                             => Status.INTERNAL.withDescription("storage operation failed").withCause(error).asException()

  private def invalidStatus(error: Throwable): StatusException =
    Status.INVALID_ARGUMENT.withDescription(safeMessage(error)).asException()

  private def safeMessage(error: Throwable): String =
    Option(error.getMessage).filter(_.nonEmpty).getOrElse("invalid request")
