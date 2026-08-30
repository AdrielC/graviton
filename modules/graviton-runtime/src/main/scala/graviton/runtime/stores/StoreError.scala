package graviton.runtime.stores

import graviton.core.RefinedTypeExt
import graviton.core.keys.BinaryKey
import graviton.core.locator.BlobLocator
import graviton.runtime.upload.TenantId
import io.github.iltotore.iron.constraint.collection.{MaxLength, MinLength}

import java.io.IOException
import java.nio.file.{AccessDeniedException, FileAlreadyExistsException}
import java.sql.SQLException

/** Stable operation names used by storage errors, metrics, and retry policy. */
enum StoreOperation:
  case PutBlob
  case GetBlob
  case GetRange
  case StatBlob
  case Inventory
  case InspectBlob
  case DeleteBlob
  case PutBlock
  case GetBlock
  case ExistsBlock
  case PutManifest
  case GetManifest
  case DeleteManifest
  case HealthCheck
  case Repair
  case Quarantine
  case Restore
  case PutObject
  case GetObject
  case HeadObject
  case ListObjects
  case DeleteObject
  case CopyObject
  case InventoryBlocks
  case GarbageCollect
  case Purge
  case PutKeyValue
  case GetKeyValue
  case DeleteKeyValue
  case ReadReplicas
  case UpdateReplicas
  case ReadRanges
  case MergeRanges
  case ResolveTenant

type StoreBackend = StoreBackend.T
object StoreBackend extends RefinedTypeExt[String, MinLength[1] & MaxLength[128]]:
  val Runtime: StoreBackend    = applyUnsafe("runtime")
  val Filesystem: StoreBackend = applyUnsafe("filesystem")
  val PostgreSql: StoreBackend = applyUnsafe("postgresql")
  val S3: StoreBackend         = applyUnsafe("s3")
  val InMemory: StoreBackend   = applyUnsafe("in-memory")
  val RocksDb: StoreBackend    = applyUnsafe("rocksdb")

/**
 * Expected failures at a storage boundary.
 *
 * Backend exceptions remain available as causes for diagnostics, but callers
 * never need to inspect an arbitrary `Throwable` to choose retry or recovery.
 */
sealed abstract class StoreError(
  val operation: StoreOperation,
  message: String,
  cause: Throwable | Null = null,
) extends Exception(message, cause):
  def retryable: Boolean

object StoreError:

  final case class InvalidInput(
    override val operation: StoreOperation,
    reason: String,
  ) extends StoreError(operation, reason):
    override val retryable: Boolean = false

  final case class NotFound(
    override val operation: StoreOperation,
    key: BinaryKey,
  ) extends StoreError(operation, s"${key.bits.render} was not found"):
    override val retryable: Boolean = false

  final case class ObjectNotFound(
    override val operation: StoreOperation,
    locator: BlobLocator,
  ) extends StoreError(operation, s"${locator.render} was not found"):
    override val retryable: Boolean = false

  final case class Conflict(
    override val operation: StoreOperation,
    reason: String,
  ) extends StoreError(operation, reason):
    override val retryable: Boolean = false

  final case class MissingTenantContext(
    override val operation: StoreOperation
  ) extends StoreError(operation, "no validated tenant context is active"):
    override val retryable: Boolean = false

  final case class TenantNotConfigured(
    override val operation: StoreOperation,
    tenantId: TenantId,
  ) extends StoreError(operation, s"tenant ${tenantId.value} is not configured"):
    override val retryable: Boolean = false

  final case class TenantSuspended(
    override val operation: StoreOperation,
    tenantId: TenantId,
  ) extends StoreError(operation, s"tenant ${tenantId.value} is suspended"):
    override val retryable: Boolean = false

  final case class TenantAdmissionUnavailable(
    override val operation: StoreOperation
  ) extends StoreError(operation, "tenant admission capacity is unavailable"):
    override val retryable: Boolean = true

  final case class TenantConcurrencyExceeded(
    override val operation: StoreOperation
  ) extends StoreError(operation, "tenant concurrent operation limit was exceeded"):
    override val retryable: Boolean = true

  final case class TenantStorageQuotaExceeded(
    override val operation: StoreOperation,
    limitBytes: Long,
    retainedBytes: Long,
    attemptedAdditionalBytes: Long,
  ) extends StoreError(
        operation,
        s"tenant retained-byte quota exceeded: limit=$limitBytes retained=$retainedBytes additional=$attemptedAdditionalBytes",
      ):
    override val retryable: Boolean = false

  final case class CorruptData(
    override val operation: StoreOperation,
    reason: String,
    underlying: Throwable | Null = null,
  ) extends StoreError(operation, reason, underlying):
    override val retryable: Boolean = false

  final case class CapacityExceeded(
    override val operation: StoreOperation,
    limitBytes: Long,
    actualBytes: Option[Long],
  ) extends StoreError(
        operation,
        actualBytes.fold(s"storage limit of $limitBytes bytes was exceeded")(actual =>
          s"storage limit of $limitBytes bytes was exceeded by a request for $actual bytes"
        ),
      ):
    override val retryable: Boolean = false

  final case class QuorumUnavailable(
    override val operation: StoreOperation,
    required: Int,
    succeeded: Int,
    total: Int,
  ) extends StoreError(
        operation,
        s"storage quorum unavailable: required=$required succeeded=$succeeded total=$total",
      ):
    override val retryable: Boolean = true

  final case class NoHealthyReplica(
    override val operation: StoreOperation,
    key: BinaryKey.Block,
    failures: Map[String, String],
  ) extends StoreError(
        operation,
        s"no healthy replica for ${key.bits.render}; checked ${failures.keys.toList.sorted.mkString(",")}",
      ):
    override val retryable: Boolean = true

  final case class PermissionDenied(
    override val operation: StoreOperation,
    backend: StoreBackend,
    underlying: Throwable | Null = null,
  ) extends StoreError(operation, s"${backend.value} denied the storage operation", underlying):
    override val retryable: Boolean = false

  final case class Unavailable(
    override val operation: StoreOperation,
    backend: StoreBackend,
    underlying: Throwable,
  ) extends StoreError(operation, s"${backend.value} is unavailable", underlying):
    override val retryable: Boolean = true

  final case class BackendFailure(
    override val operation: StoreOperation,
    backend: StoreBackend,
    underlying: Throwable,
    override val retryable: Boolean,
  ) extends StoreError(operation, s"${backend.value} failed during ${operation.toString}", underlying)

  def fromThrowable(
    operation: StoreOperation,
    backend: StoreBackend = StoreBackend.Runtime,
    retryUnknown: Boolean = false,
  )(error: Throwable): StoreError =
    error match
      case typed: StoreError                 => typed
      case invalid: IllegalArgumentException => InvalidInput(operation, Option(invalid.getMessage).getOrElse("invalid storage input"))
      case invalid: IllegalStateException    =>
        CorruptData(operation, Option(invalid.getMessage).getOrElse("invalid storage state"), invalid)
      case _: FileAlreadyExistsException     => Conflict(operation, "the storage resource already exists")
      case denied: AccessDeniedException     => PermissionDenied(operation, backend, denied)
      case unavailable: IOException          => Unavailable(operation, backend, unavailable)
      case unavailable: SQLException         => Unavailable(operation, backend, unavailable)
      case other                             => BackendFailure(operation, backend, other, retryUnknown)
