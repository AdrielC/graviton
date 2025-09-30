package graviton.db

import zio.Chunk
import zio.json.ast.Json
import io.github.iltotore.iron.{zio as _, *}
import io.github.iltotore.iron.constraint.all.*

private[db] type ExactLength[N <: Int] = Length[StrictEqual[N]]

type HashBytes  = Chunk[Byte] :| (MinLength[16] & MaxLength[64])
type SmallBytes = Chunk[Byte] :| MaxLength[1048576]

type PosLong    = Long :| Positive
type NonNegLong = Long :| Not[Negative]

type BlockInsert = (key: BlockKey, offset: NonNegLong, length: PosLong)

type StoreKey = Chunk[Byte] :| ExactLength[32]
object StoreKey extends RefinedSubtype[Chunk[Byte], ExactLength[32]]

final case class BlockKey(algoId: Short, hash: HashBytes)

final case class BlobKey(
  algoId: Short,
  hash: HashBytes,
  size: PosLong,
  mediaTypeHint: Option[String],
)

enum StoreStatus(val dbValue: String) derives CanEqual:
  case Active extends StoreStatus("active")
  case Paused extends StoreStatus("paused")
  case Retired extends StoreStatus("retired")

object StoreStatus:
  private val lookup: Map[String, StoreStatus] = values.map(status => status.dbValue -> status).toMap

  def fromDbValue(value: String): StoreStatus =
    lookup.getOrElse(
      value.toLowerCase(java.util.Locale.ROOT),
      throw IllegalArgumentException(s"Unknown store status '$value'"),
    )

enum LocationStatus(val dbValue: String) derives CanEqual:
  case Active     extends LocationStatus("active")
  case Stale      extends LocationStatus("stale")
  case Missing    extends LocationStatus("missing")
  case Deprecated extends LocationStatus("deprecated")
  case Error      extends LocationStatus("error")

object LocationStatus:
  private val lookup: Map[String, LocationStatus] = values.map(status => status.dbValue -> status).toMap

  def fromDbValue(value: String): LocationStatus =
    lookup.getOrElse(
      value.toLowerCase(java.util.Locale.ROOT),
      throw IllegalArgumentException(s"Unknown location status '$value'"),
    )

final case class BlobStoreRow(
  key: StoreKey,
  implId: String,
  buildFp: Chunk[Byte],
  dvSchemaUrn: String,
  dvCanonical: Chunk[Byte],
  dvJsonPreview: Option[Json],
  status: StoreStatus,
  version: Long,
)
