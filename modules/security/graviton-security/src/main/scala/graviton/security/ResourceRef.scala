package graviton.security

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
