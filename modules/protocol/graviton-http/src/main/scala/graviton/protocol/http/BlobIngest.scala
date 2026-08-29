package graviton.protocol.http

import graviton.core.attributes.IngestStats
import graviton.core.keys.BinaryKey
import graviton.pdf.PdfUploadSupport
import graviton.runtime.stores.BlobStore
import graviton.runtime.upload.{LocalityAwareUpload, UploadIngestor, UploadIntent, UploadNode, UploadSessionKey}
import zio.*
import zio.stream.ZStream

/** One streaming ingest path shared by the public API and local operator UI. */
trait BlobIngest:
  def upload(
    session: Option[UploadSessionKey],
    intent: UploadIntent,
    bytes: ZStream[Any, Throwable, Byte],
  ): IO[BlobIngest.Error, BlobIngest.Result]

object BlobIngest:
  final case class Result(
    key: BinaryKey.Blob,
    stats: IngestStats,
    owner: Option[UploadNode],
  )

  sealed trait Error extends Exception

  object Error:
    final case class InvalidInput(message: String)                    extends Exception(message) with Error
    final case class Rejected(cause: HttpSecurityPolicy.BodyRejected) extends Exception(cause.getMessage, cause) with Error
    case object LocalityUnavailable                                   extends Exception("Upload locality is not enabled on this server") with Error
    final case class Locality(cause: LocalityAwareUpload.Error)
        extends Exception("Upload locality could not complete the stream", cause)
        with Error
    final case class Storage(cause: Throwable)                        extends Exception("Blob ingest failed", cause) with Error

  def make(blobStore: BlobStore, localizedUpload: Option[LocalityAwareUpload]): BlobIngest =
    make(PdfUploadSupport.ingestor(blobStore), localizedUpload)

  def make(uploadIngestor: UploadIngestor, localizedUpload: Option[LocalityAwareUpload]): BlobIngest =
    new Live(uploadIngestor, localizedUpload)

  def upload(
    session: Option[UploadSessionKey],
    intent: UploadIntent,
    bytes: ZStream[Any, Throwable, Byte],
  ): ZIO[BlobIngest, Error, Result] =
    ZIO.serviceWithZIO[BlobIngest](_.upload(session, intent, bytes))

  def layer(
    localizedUpload: Option[LocalityAwareUpload]
  ): ZLayer[BlobStore, Nothing, BlobIngest] =
    ZLayer.fromFunction((blobStore: BlobStore) => make(blobStore, localizedUpload))

  def configuredLayer(
    localizedUpload: Option[LocalityAwareUpload]
  ): ZLayer[UploadIngestor, Nothing, BlobIngest] =
    ZLayer.fromFunction((uploadIngestor: UploadIngestor) => make(uploadIngestor, localizedUpload))

  private final class Live(
    uploadIngestor: UploadIngestor,
    localizedUpload: Option[LocalityAwareUpload],
  ) extends BlobIngest:
    override def upload(
      session: Option[UploadSessionKey],
      intent: UploadIntent,
      bytes: ZStream[Any, Throwable, Byte],
    ): IO[Error, Result] =
      (session, localizedUpload) match
        case (Some(key), Some(localized)) =>
          localized
            .upload(key, intent, bytes)
            .map(result => Result(result.key, result.stats, Some(result.owner)))
            .mapError(Error.Locality(_))
        case (Some(_), None)              =>
          ZIO.fail(Error.LocalityUnavailable)
        case _                            =>
          uploadIngestor
            .put(intent, bytes)
            .map(result => Result(result.stored.key, result.stored.stats, None))
            .mapError {
              case UploadIngestor.Error.InvalidInput(message)                          => Error.InvalidInput(message)
              case mismatch: UploadIngestor.Error.MediaTypeMismatch                    => Error.InvalidInput(mismatch.getMessage)
              case ambiguous: UploadIngestor.Error.AmbiguousDetection                  => Error.InvalidInput(ambiguous.getMessage)
              case validation: UploadIngestor.Error.Validation                         => Error.InvalidInput(validation.getMessage)
              case UploadIngestor.Error.Source(error: HttpSecurityPolicy.BodyRejected) => Error.Rejected(error)
              case UploadIngestor.Error.Storage(error: IllegalArgumentException)       =>
                Error.InvalidInput(Option(error.getMessage).getOrElse("Invalid blob"))
              case error: UploadIngestor.Error                                         => Error.Storage(error)
            }
