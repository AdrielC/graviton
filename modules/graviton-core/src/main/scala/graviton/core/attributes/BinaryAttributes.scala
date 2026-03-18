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
    // SAFETY: compile-time constant matching IdentifierConstraint
    val identifier = Identifier.applyUnsafe("graviton.size")

  case object ChunkCount extends BinaryAttributeKey[ChunkCount]:
    // SAFETY: compile-time constant matching IdentifierConstraint
    val identifier = Identifier.applyUnsafe("graviton.chunk-count")

  case object Mime extends BinaryAttributeKey[Mime]:
    // SAFETY: compile-time constant matching IdentifierConstraint
    val identifier = Identifier.applyUnsafe("graviton.mime")

  final case class Digest(algo: Algo) extends BinaryAttributeKey[HexLower]:
    // SAFETY: algo.value is pre-refined to AlgoConstraint; "graviton.digest.<algo>" matches IdentifierConstraint
    val identifier: Identifier = Identifier.applyUnsafe(s"graviton.digest.${algo.value}")

  final case class Custom(name: CustomAttributeName) extends BinaryAttributeKey[CustomAttributeValue]:
    // SAFETY: name.value is pre-refined to IdentifierConstraint & MaxLength[64]; "user.<name>" matches IdentifierConstraint
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

  def record(event: String, at: Instant): BinaryAttributes =
    copy(history = history :+ (event -> at))

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
   * Validate that confirmed attributes are consistent with advertised ones.
   *
   * When a caller advertises an attribute (e.g. size or digest) and the system
   * later confirms a different value from the actual ingest measurement, the
   * mismatch is surfaced here as an `Either[String, BinaryAttributes]`.  A
   * mismatch indicates the client supplied incorrect metadata, which could
   * silently corrupt verification logic if ignored.
   *
   * Attributes that are only advertised or only confirmed are always valid —
   * the check fires only when both sides carry a value for the same key and
   * those values disagree.
   *
   * @note Digest validation is per-algorithm: only algos present in both maps
   *       are compared; extra algos on either side are ignored.
   * @return `Right(this)` if all present confirmed/advertised pairs agree,
   *         `Left(msg)` with a semicolon-separated list of mismatches otherwise.
   * @example
   *   {{{
   *   val attrs = BinaryAttributes.empty
   *     .advertiseSize(FileSize.unsafe(100L))
   *     .confirmSize(FileSize.unsafe(200L))
   *   attrs.validate // Left("size mismatch: advertised=100, confirmed=200")
   *   }}}
   */
  def validate: Either[String, BinaryAttributes] =
    // Import the custom Tag instances from BinaryAttr so that the inline
    // Record.selectDynamic calls inside digestsValue / customValue resolve to
    // the same Tag representation that was used when building the Record.
    // Without this, the Tag for e.g. Option[Map[Algo, HexLower]] differs
    // between the build site and the lookup site, causing NoSuchElementException.
    import BinaryAttr.given

    val errors = List.newBuilder[String]

    (advertised.sizeValue, confirmed.sizeValue) match
      case (Some(adv), Some(conf)) if adv != conf =>
        errors += s"size mismatch: advertised=$adv, confirmed=$conf"
      case _                                      => ()

    (advertised.chunkCountValue, confirmed.chunkCountValue) match
      case (Some(adv), Some(conf)) if adv != conf =>
        errors += s"chunkCount mismatch: advertised=$adv, confirmed=$conf"
      case _                                      => ()

    (advertised.mimeValue, confirmed.mimeValue) match
      case (Some(adv), Some(conf)) if adv != conf =>
        errors += s"mime mismatch: advertised=$adv, confirmed=$conf"
      case _                                      => ()

    for
      advDigests  <- advertised.digestsValue.toList
      confDigests <- confirmed.digestsValue.toList
      algo        <- (advDigests.keySet intersect confDigests.keySet).toList
      advHex       = advDigests(algo)
      confHex      = confDigests(algo)
      if advHex != confHex
    do errors += s"digest mismatch for $algo: advertised=$advHex, confirmed=$confHex"

    val errs = errors.result()
    if errs.isEmpty then Right(this) else Left(errs.mkString("; "))

  def diff: DiffRecord =
    BinaryAttrDiff.compute(advertised, confirmed)

  def advertisedRecord: BinaryAttr.Partial = advertised
  def confirmedRecord: BinaryAttr.Partial  = confirmed

  private def modifyAdvertised(f: BinaryAttr.Partial => BinaryAttr.Partial): BinaryAttributes =
    copy(advertised = f(advertised))

  private def modifyConfirmed(f: BinaryAttr.Partial => BinaryAttr.Partial): BinaryAttributes =
    copy(confirmed = f(confirmed))
end BinaryAttributes
