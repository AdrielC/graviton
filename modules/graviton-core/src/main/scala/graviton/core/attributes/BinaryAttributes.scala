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

  private def validateRecord(label: String, record: BinaryAttr.Partial): Either[String, Unit] =
    for
      _ <- validateSizeAndChunkCount(label, record.sizeValue, record.chunkCountValue)
      _ <- validateMime(label, record.mimeValue)
      _ <- validateDigests(label, record.digestsOrEmpty)
    yield ()

  private def validateSizeAndChunkCount(
    label: String,
    size: Option[FileSize],
    chunkCount: Option[ChunkCount],
  ): Either[String, Unit] =
    (size, chunkCount) match
      case (Some(fileSize), Some(chunks)) =>
        Either.cond(
          chunks.value <= fileSize.value,
          (),
          s"$label chunk count ${chunks.value} cannot exceed size ${fileSize.value}",
        )
      case _                              =>
        Right(())

  private def validateMime(label: String, mime: Option[Mime]): Either[String, Unit] =
    mime match
      case None        => Right(())
      case Some(value) =>
        val raw       = value.value
        val mediaType = raw.takeWhile(_ != ';')
        val parts     = mediaType.split("/", -1)
        Either.cond(
          raw == raw.trim &&
            parts.length == 2 &&
            parts.forall(part => part.nonEmpty && !part.exists(_.isWhitespace)),
          (),
          s"$label mime must be a valid media type, got '$raw'",
        )

  private def validateDigests(label: String, digests: Map[Algo, HexLower]): Either[String, Unit] =
    digests.foldLeft[Either[String, Unit]](Right(())) { case (acc, (algo, hex)) =>
      acc.flatMap(_ => validateDigest(algo, hex).left.map(msg => s"$label digest '${algo.value}' invalid: $msg"))
    }
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
   * Validate internal invariants.
   *
   * This checks cross-field invariants that the refined wrappers alone cannot encode,
   * such as digest length by algorithm, MIME structure, and impossible size/chunk-count pairs.
   */
  def validate: Either[String, BinaryAttributes] =
    for
      _ <- BinaryAttributes.validateRecord("advertised", advertised)
      _ <- BinaryAttributes.validateRecord("confirmed", confirmed)
      _ <- BinaryAttributes.validateSizeAndChunkCount("merged", size, chunkCount)
    yield this

  def diff: DiffRecord =
    BinaryAttrDiff.compute(advertised, confirmed)

  def advertisedRecord: BinaryAttr.Partial = advertised
  def confirmedRecord: BinaryAttr.Partial  = confirmed

  private def modifyAdvertised(f: BinaryAttr.Partial => BinaryAttr.Partial): BinaryAttributes =
    copy(advertised = f(advertised))

  private def modifyConfirmed(f: BinaryAttr.Partial => BinaryAttr.Partial): BinaryAttributes =
    copy(confirmed = f(confirmed))
end BinaryAttributes
