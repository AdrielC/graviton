package graviton.core.attributes

import BinaryAttr.Access.*
import BinaryAttr.PartialOps.*
import BinaryAttrDiff.Record as DiffRecord
import graviton.core.types.{CustomAttributeName, CustomAttributeValue, Identifier}
import graviton.core.types.*

import java.time.Instant
import scala.collection.immutable.ListMap

sealed trait BinaryAttributeKey[A] extends Product with Serializable:
  def identifier: Identifier

object BinaryAttributeKey:
  case object Size extends BinaryAttributeKey[FileSize]:
    val identifier = Identifier.applyUnsafe("graviton.size")

  case object ChunkCount extends BinaryAttributeKey[ChunkCount]:
    val identifier = Identifier.applyUnsafe("graviton.chunk-count")

  case object Mime extends BinaryAttributeKey[Mime]:
    val identifier = Identifier.applyUnsafe("graviton.mime")

  final case class Digest(algo: Algo) extends BinaryAttributeKey[HexLower]:
    val identifier: Identifier = Identifier.applyUnsafe(s"graviton.digest.${algo.value}")

  final case class Custom(name: CustomAttributeName) extends BinaryAttributeKey[CustomAttributeValue]:
    val identifier: Identifier = Identifier.applyUnsafe(s"user.${name.value}")

object BinaryAttributes:

  val empty: BinaryAttributes =
    BinaryAttributes(
      advertised = BinaryAttr.partial(),
      confirmed = BinaryAttr.partial(),
      history = Vector.empty,
    )

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

  def advertiseCustom(name: CustomAttributeName, value: CustomAttributeValue): BinaryAttributes =
    modifyAdvertised { record =>
      val next = record.customOrEmpty + (name -> value)
      record.copyValues(custom = Some(next))
    }

  def confirmCustom(name: CustomAttributeName, value: CustomAttributeValue): BinaryAttributes =
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

  /**
   * Validate internal invariants.
   *
   * Custom attribute keys/values are already refined at the type level, so this is currently total.
   */
  def validate: Either[Nothing, BinaryAttributes] =
    Right(this)

  def diff: DiffRecord =
    BinaryAttrDiff.compute(advertised, confirmed)

  def advertisedRecord: BinaryAttr.Partial = advertised
  def confirmedRecord: BinaryAttr.Partial  = confirmed

  private def modifyAdvertised(f: BinaryAttr.Partial => BinaryAttr.Partial): BinaryAttributes =
    copy(advertised = f(advertised))

  private def modifyConfirmed(f: BinaryAttr.Partial => BinaryAttr.Partial): BinaryAttributes =
    copy(confirmed = f(confirmed))
end BinaryAttributes
