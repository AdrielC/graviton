package graviton.security

/**
 * Capability bitmask shared with the database column
 * `quasar.acl_entry.capabilities` (bigint). Each capability occupies one
 * bit; the 64-bit space is more than enough for the foreseeable surface.
 *
 * Bit layout is stable: adding a new capability appends a bit; bits are
 * never re-numbered or removed without a coordinated migration.
 */
enum Capability(val bit: Long):
  case BlobRead          extends Capability(1L << 0)
  case BlobWrite         extends Capability(1L << 1)
  case BlobDelete        extends Capability(1L << 2)
  case DocumentRead      extends Capability(1L << 3)
  case DocumentWrite     extends Capability(1L << 4)
  case DocumentDelete    extends Capability(1L << 5)
  case NamespaceAdmin    extends Capability(1L << 6)
  case AclAdmin          extends Capability(1L << 7)
  case ObservabilityRead extends Capability(1L << 8)
  case AuditRead         extends Capability(1L << 9)
  case LegalHoldWrite    extends Capability(1L << 10)

object Capability:
  /** Parse an OAuth-style `scope` string of space-separated tokens. */
  def fromScopeString(scopes: String): CapabilitySet =
    if scopes == null || scopes.isEmpty then CapabilitySet.empty
    else
      scopes
        .split("\\s+")
        .iterator
        .flatMap(fromScopeToken)
        .foldLeft(CapabilitySet.empty)(_ + _)

  private def fromScopeToken(token: String): Option[Capability] =
    token match
      case "blob.read"          => Some(BlobRead)
      case "blob.write"         => Some(BlobWrite)
      case "blob.delete"        => Some(BlobDelete)
      case "doc.read"           => Some(DocumentRead)
      case "doc.write"          => Some(DocumentWrite)
      case "doc.delete"         => Some(DocumentDelete)
      case "ns.admin"           => Some(NamespaceAdmin)
      case "acl.admin"          => Some(AclAdmin)
      case "observability.read" => Some(ObservabilityRead)
      case "audit.read"         => Some(AuditRead)
      case "legal_hold.write"   => Some(LegalHoldWrite)
      case _                    => None

/**
 * Immutable set of capabilities backed by a 64-bit mask. Supports union with
 * ACL-stored masks for effective-permission calculation.
 */
final case class CapabilitySet(mask: Long) extends AnyVal:
  def contains(cap: Capability): Boolean             = (mask & cap.bit) != 0L
  def +(cap: Capability): CapabilitySet              = CapabilitySet(mask | cap.bit)
  def ++(other: CapabilitySet): CapabilitySet        = CapabilitySet(mask | other.mask)
  def intersect(other: CapabilitySet): CapabilitySet = CapabilitySet(mask & other.mask)
  def nonEmpty: Boolean                              = mask != 0L

object CapabilitySet:
  val empty: CapabilitySet = CapabilitySet(0L)

  def of(caps: Capability*): CapabilitySet =
    caps.foldLeft(empty)(_ + _)

  def fromMask(mask: Long): CapabilitySet = CapabilitySet(mask)
