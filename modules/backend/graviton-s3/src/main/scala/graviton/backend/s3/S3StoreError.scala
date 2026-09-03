package graviton.backend.s3

import graviton.core.keys.BinaryKey
import graviton.runtime.stores.{StoreBackend, StoreError, StoreOperation}
import software.amazon.awssdk.core.exception.SdkClientException
import software.amazon.awssdk.services.s3.model.S3Exception

/** S3-specific status classification kept out of the backend-neutral runtime. */
private[s3] object S3StoreError:

  def fromThrowable(
    operation: StoreOperation,
    key: Option[BinaryKey] = None,
  )(error: Throwable): StoreError =
    error match
      case typed: StoreError       => typed
      case s3: S3Exception         => classifyS3(operation, key, s3)
      case sdk: SdkClientException => StoreError.Unavailable(operation, StoreBackend.S3, sdk)
      case other                   => StoreError.fromThrowable(operation, StoreBackend.S3, retryUnknown = false)(other)

  private def classifyS3(operation: StoreOperation, key: Option[BinaryKey], error: S3Exception): StoreError =
    val status = error.statusCode()
    val code   = Option(error.awsErrorDetails()).flatMap(details => Option(details.errorCode())).getOrElse("")

    if (status == 404 || code == "NoSuchKey" || code == "NotFound") && key.nonEmpty then StoreError.NotFound(operation, key.get)
    else if status == 401 || status == 403 then StoreError.PermissionDenied(operation, StoreBackend.S3, error)
    else if status == 409 || status == 412 then
      StoreError.Conflict(operation, s"S3 rejected the operation with status $status${if code.isEmpty then "" else s" ($code)"}")
    else if status == 408 || status == 429 || status >= 500 then StoreError.Unavailable(operation, StoreBackend.S3, error)
    else StoreError.BackendFailure(operation, StoreBackend.S3, error, retryable = false)
