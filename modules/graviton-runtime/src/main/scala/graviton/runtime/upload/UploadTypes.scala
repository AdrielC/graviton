package graviton.runtime.upload

import graviton.core.RefinedTypeExt
import graviton.core.types.IdentifierConstraint
import graviton.core.types.FileSize
import graviton.core.keys.BinaryKey
import graviton.core.attributes.IngestStats
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.all.*
import io.github.iltotore.iron.constraint.numeric
import zio.Chunk

/** Canonical lowercase UUID text used at upload protocol boundaries. */
type CanonicalUuidConstraint =
  Match["[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"]

type TenantId = TenantId.T
object TenantId extends RefinedTypeExt[String, CanonicalUuidConstraint]

type UploadSessionId = UploadSessionId.T
object UploadSessionId extends RefinedTypeExt[String, CanonicalUuidConstraint]

type UploadNodeId = UploadNodeId.T
object UploadNodeId extends RefinedTypeExt[String, IdentifierConstraint]

type UploadNodeHost = UploadNodeHost.T
object UploadNodeHost extends RefinedTypeExt[String, Match["[A-Za-z0-9][A-Za-z0-9.-]{0,119}"]]

type UploadNodePort = UploadNodePort.T
object UploadNodePort extends RefinedTypeExt[Int, numeric.GreaterEqual[1] & numeric.LessEqual[65535]]

/**
 * Tenant-scoped upload identity.
 *
 * Keeping this as a named product prevents tenant and session identifiers from
 * being swapped or silently separated while crossing routing boundaries.
 */
final case class UploadSessionKey(
  tenantId: TenantId,
  uploadSessionId: UploadSessionId,
):
  private[graviton] def entityId: String = s"${tenantId.value}:${uploadSessionId.value}"

object UploadSessionKey:
  private val SeparatorIndex = 36

  def parseEntityId(value: String): Either[String, UploadSessionKey] =
    if value.length != 73 || value.charAt(SeparatorIndex) != ':' then Left("upload session entity ID must contain two canonical UUIDs")
    else
      for
        tenant  <- TenantId.either(value.substring(0, SeparatorIndex))
        session <- UploadSessionId.either(value.substring(SeparatorIndex + 1))
      yield UploadSessionKey(tenant, session)

object UploadHttpHeaders:
  val TenantId      = "X-Graviton-Tenant-Id"
  val UploadSession = "X-Graviton-Upload-Session-Id"
  val UploadLength  = "Upload-Length"
  val UploadOffset  = "Upload-Offset"
  val UploadPartId  = "Upload-Part-Id"
  val UploadExpires = "Upload-Expires"

final case class UploadNode(
  id: UploadNodeId,
  host: UploadNodeHost,
  controlPort: UploadNodePort,
  uploadPort: UploadNodePort,
)

object UploadNode:
  def fromEndpoints(
    host: UploadNodeHost,
    controlPort: UploadNodePort,
    uploadPort: UploadNodePort,
  ): UploadNode =
    // SAFETY: host is at most 120 identifier-safe characters and port is at
    // most five digits, so the rendered endpoint satisfies IdentifierConstraint.
    UploadNode(UploadNodeId.applyUnsafe(s"${host.value}:${controlPort.value}"), host, controlPort, uploadPort)

/**
 * Bounded frame used only at an upload-node transport boundary.
 *
 * Arbitrary uploads remain streams. A frame is the sole materializable
 * data-plane value and has a compile-time plus runtime 1 MiB ceiling.
 */
type UploadTransportFrame = Chunk[Byte] :| UploadTransportFrame.Constraint

object UploadTransportFrame:
  type Constraint = MinLength[1] & MaxLength[1048576]
  inline val MaxBytes = 1048576

  def fromChunk(bytes: Chunk[Byte]): Either[String, UploadTransportFrame] =
    bytes.refineEither[Constraint]

  extension (frame: UploadTransportFrame)
    def bytes: Chunk[Byte] = frame
    def length: Int        = frame.length

final case class UploadShardAssignment(
  shardId: Int,
  controlHost: UploadNodeHost,
  controlPort: UploadNodePort,
)

final case class UploadIntent(
  contentType: zio.blocks.mediatype.MediaType,
  expectedSize: Option[FileSize],
)

final case class LocalizedUploadResult(
  key: BinaryKey.Blob,
  stats: IngestStats,
  owner: UploadNode,
)
