package graviton.core.attributes

import BinaryAttr.Access.*
import BinaryAttr.PartialOps.*
import BinaryAttrDiff.Record as DiffRecord
import graviton.core.keys.BinaryKey
import graviton.core.locator.BlobLocator
import graviton.core.model.{ChunkCount, FileSize}
import graviton.core.types.*

import java.time.Instant
import scala.collection.immutable.ListMap

sealed trait BinaryAttributeKey[A] extends Product with Serializable:
  def identifier: String

object BinaryAttributeKey:
  case object Size extends BinaryAttributeKey[FileSize]:
    val identifier = "graviton.size"

  case object ChunkCount extends BinaryAttributeKey[ChunkCount]:
    val identifier = "graviton.chunk-count"

  case object Mime extends BinaryAttributeKey[Mime]:
    val identifier = "graviton.mime"

  final case class Digest(algo: Algo) extends BinaryAttributeKey[HexLower]:
    val identifier: String = s"graviton.digest.$algo"

  final case class Custom(name: String) extends BinaryAttributeKey[String]:
    val identifier: String = s"user.$name"

object BinaryAttributes:

  val empty: BinaryAttributes =
    BinaryAttributes(
      advertised = BinaryAttr.partial(),
      confirmed = BinaryAttr.partial(),
      history = Vector.empty,
    )

  enum ValidationError derives CanEqual:
    case InvalidCustomKey(name: String)

    def message: String = this match
      case InvalidCustomKey(name) =>
        s"Custom attribute name '$name' must match ${BinaryAttributes.customKeyPattern}"

  private val customKeyPattern = "^[A-Za-z0-9][A-Za-z0-9._:-]{0,63}$"

  private[attributes] def isValidCustomKey(name: String): Boolean =
    name.nonEmpty && name.length <= 64 && name.matches(customKeyPattern)

  private def entriesOf(record: BinaryAttr.Partial): ListMap[BinaryAttributeKey[?], Any] =
    val builder = ListMap.newBuilder[BinaryAttributeKey[?], Any]
    record.sizeValue.foreach(value => builder += BinaryAttributeKey.Size -> value)
    record.chunkCountValue.foreach(value => builder += BinaryAttributeKey.ChunkCount -> value)
    record.mimeValue.foreach(value => builder += BinaryAttributeKey.Mime -> value)
    record.digestsValue.foreach(_.foreach { case (algo, value) =>
      builder += (BinaryAttributeKey.Digest(algo) -> value)
    })
    record.customValue.foreach(_.foreach { case (name, value) =>
      builder += (BinaryAttributeKey.Custom(name) -> value)
    })
    builder.result()

  private def firstInvalidCustom(record: BinaryAttr.Partial): Option[String] =
    record.customValue
      .getOrElse(Map.empty)
      .keysIterator
      .find(name => !isValidCustomKey(name))
end BinaryAttributes

final case class BinaryAttributes private (
  advertised: BinaryAttr.Partial,
  confirmed: BinaryAttr.Partial,
  history: Vector[(String, Instant)],
):
  import BinaryAttributes.*

  def record(event: String): BinaryAttributes =
    copy(history = history :+ (event -> Instant.now()))

  def advertiseSize(value: FileSize): BinaryAttributes =
    modifyAdvertised(_.copyValues(size = Some(value)))

  def confirmSize(value: FileSize): BinaryAttributes =
    modifyConfirmed(_.copyValues(size = Some(value)))

  def advertiseChunkCount(value: ChunkCount): BinaryAttributes =
    modifyAdvertised(_.copyValues(chunkCount = Some(value)))

  def confirmChunkCount(value: ChunkCount): BinaryAttributes =
    modifyConfirmed(_.copyValues(chunkCount = Some(value)))

  def advertiseMime(value: Mime): BinaryAttributes =
    modifyAdvertised(_.copyValues(mime = Some(value)))

  def confirmMime(value: Mime): BinaryAttributes =
    modifyConfirmed(_.copyValues(mime = Some(value)))

  def advertiseDigest(algo: Algo, value: HexLower): BinaryAttributes =
    modifyAdvertised { record =>
      val next = record.digestsOrEmpty + (algo -> value)
      record.copyValues(digests = Some(next))
    }

  def confirmDigest(algo: Algo, value: HexLower): BinaryAttributes =
    modifyConfirmed { record =>
      val next = record.digestsOrEmpty + (algo -> value)
      record.copyValues(digests = Some(next))
    }

  def advertiseCustom(name: String, value: String): BinaryAttributes =
    modifyAdvertised { record =>
      val next = record.customOrEmpty + (name -> value)
      record.copyValues(custom = Some(next))
    }

  def confirmCustom(name: String, value: String): BinaryAttributes =
    modifyConfirmed { record =>
      val next = record.customOrEmpty + (name -> value)
      record.copyValues(custom = Some(next))
    }

  def size: Option[FileSize] =
    confirmed.sizeValue.orElse(advertised.sizeValue)

  def chunkCount: Option[ChunkCount] =
    confirmed.chunkCountValue.orElse(advertised.chunkCountValue)

  def mime: Option[Mime] =
    confirmed.mimeValue.orElse(advertised.mimeValue)

  def digest(algo: Algo): Option[HexLower] =
    confirmed.digestsValue.flatMap(_.get(algo)).orElse(advertised.digestsValue.flatMap(_.get(algo)))

  def advertisedEntries: ListMap[BinaryAttributeKey[?], Any] =
    entriesOf(advertised)

  def confirmedEntries: ListMap[BinaryAttributeKey[?], Any] =
    entriesOf(confirmed)

  def validate: Either[ValidationError, BinaryAttributes] =
    advertisedInvalid
      .orElse(confirmedInvalid)
      .map(ValidationError.InvalidCustomKey.apply)
      .map(Left(_))
      .getOrElse(Right(this))

  def diff: DiffRecord =
    BinaryAttrDiff.compute(advertised, confirmed)

  def advertisedRecord: BinaryAttr.Partial = advertised
  def confirmedRecord: BinaryAttr.Partial  = confirmed

  private def advertisedInvalid: Option[String] = firstInvalidCustom(advertised)

  private def confirmedInvalid: Option[String] = firstInvalidCustom(confirmed)

  private def modifyAdvertised(f: BinaryAttr.Partial => BinaryAttr.Partial): BinaryAttributes =
    copy(advertised = f(advertised))

  private def modifyConfirmed(f: BinaryAttr.Partial => BinaryAttr.Partial): BinaryAttributes =
    copy(confirmed = f(confirmed))
end BinaryAttributes

final case class BlobWriteResult(
  key: BinaryKey,
  locator: BlobLocator,
  attributes: BinaryAttributes,
)
