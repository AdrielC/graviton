package graviton.security

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID

/**
 * Typed resource reference mirroring the `quasar.resource_kind` enum. Passed
 * to [[CapabilityCheck]] so both the JWT scope and ACL table agree on what
 * is being protected.
 */
enum ResourceKind(val dbValue: String):
  case Blob      extends ResourceKind("blob")
  case Document  extends ResourceKind("document")
  case Folder    extends ResourceKind("folder")
  case Namespace extends ResourceKind("namespace")
  case Schema    extends ResourceKind("schema")

object ResourceKind:
  def fromString(raw: String): Option[ResourceKind] =
    values.find(_.dbValue == raw)

/**
 * A concrete resource being accessed. `id = None` represents a collection-
 * scoped action (e.g. list / create in a folder).
 */
final case class ResourceRef(kind: ResourceKind, id: Option[UUID]):
  def dbKind: String = kind.dbValue

object ResourceRef:
  private val BlobNamespace = UUID.fromString("6f9c6cd8-fd9c-5f53-9e18-29a7507730d4")

  val blobCollection: ResourceRef = ResourceRef(ResourceKind.Blob, None)

  /**
   * Stable UUIDv5 projection for content keys. ACL rows use UUID resource IDs,
   * while CAS keys are textual digests. The projection is deterministic and
   * namespaced, so every node derives the same ACL resource without a lookup.
   */
  def blob(contentKey: String): ResourceRef =
    ResourceRef(ResourceKind.Blob, Some(uuidV5(BlobNamespace, contentKey)))

  private def uuidV5(namespace: UUID, name: String): UUID =
    val bytes  = ByteBuffer
      .allocate(16)
      .putLong(namespace.getMostSignificantBits)
      .putLong(namespace.getLeastSignificantBits)
      .array()
    val digest = MessageDigest.getInstance("SHA-1")
    digest.update(bytes)
    digest.update(name.getBytes(StandardCharsets.UTF_8))
    val hash   = digest.digest()
    hash(6) = ((hash(6) & 0x0f) | 0x50).toByte
    hash(8) = ((hash(8) & 0x3f) | 0x80).toByte
    val value  = ByteBuffer.wrap(hash)
    UUID(value.getLong(), value.getLong())
