package torrent

/**
 * Base trait for all errors that can occur in the Torrent system
 */
sealed trait TorrentError extends Throwable:
  def message: String
  override def getMessage: String = message

/**
 * Errors related to storage operations
 */
sealed trait StorageError extends TorrentError

object StorageError:

  sealed trait AccessError extends StorageError:
    override def getCause: Throwable = super.getCause

  /**
   * Error reading from storage
   */
  final case class SystemError(cause: Throwable) extends AccessError:
    override def message: String     = s"System error: ${cause.getMessage}"
    override def getCause: Throwable = cause

  sealed trait OperationError extends StorageError:
    override def getCause: Throwable = super.getCause

  /**
   * File not found
   */
  final case class NotFound(fileId: BinaryKey) extends OperationError, AccessError:
    override def message: String = s"File not found: ${fileId.value}"

  /**
   * Error reading from storage
   */
  final case class ReadError(cause: Throwable) extends OperationError, AccessError:
    override def message: String     = s"Error reading file: ${cause.getMessage}"
    override def getCause: Throwable = cause

  /**
   * Error writing to storage
   */
  final case class WriteError(cause: Throwable) extends OperationError:
    override def message: String     = s"Error writing file: ${cause.getMessage}"
    override def getCause: Throwable = cause

  /**
   * Error deleting from storage
   */
  final case class DeleteError(fileId: BinaryKey, cause: Throwable) extends OperationError:
    override def message: String     = s"Error deleting file ${fileId.value}: ${cause.getMessage}"
    override def getCause: Throwable = cause

/**
 * Errors related to validation
 */
sealed trait ValidationError extends TorrentError

object ValidationError:
  /**
   * File size exceeds maximum allowed
   */
  final case class FileTooLarge(size: Long, maxSize: Long) extends ValidationError:
    override def message: String = s"File size $size exceeds maximum allowed size of $maxSize bytes"

  /**
   * File type not allowed
   */
  final case class UnsupportedFileType(contentType: String) extends ValidationError:
    override def message: String = s"Unsupported file type: $contentType"

  /**
   * Missing required field
   */
  final case class MissingField(fieldName: String) extends ValidationError:
    override def message: String = s"Missing required field: $fieldName"
