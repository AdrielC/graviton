package graviton.core.attributes

import graviton.core.keys.BinaryKey
import graviton.core.locator.BlobLocator
import graviton.core.model.{ChunkCount, FileSize}
import graviton.core.types.*
import zio.schema.DeriveSchema

import java.time.Instant
import scala.collection.immutable.ListMap

enum Source:
  case Sniffed, Derived, ProvidedUser, ProvidedSystem, Verified

final case class Tracked[+A](
  value: A,
  source: Source,
  at: Instant,
  note: Option[String] = None,
)

object Tracked:
  def now[A](value: A, source: Source, note: Option[String] = None): Tracked[A] =
    Tracked(value, source, Instant.now(), note)

  def merge[A](left: Tracked[A], right: Tracked[A]): Tracked[A] =
    val precedence: Source => Int =
      case Source.Verified       => 5
      case Source.ProvidedUser   => 4
      case Source.ProvidedSystem => 3
      case Source.Derived        => 2
      case Source.Sniffed        => 1

    val leftRank  = precedence(left.source)
    val rightRank = precedence(right.source)

    if leftRank > rightRank then left
    else if rightRank > leftRank then right
    else if left.at.isAfter(right.at) then left
    else right

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
  val empty: BinaryAttributes = BinaryAttributes()

  enum ValidationError derives CanEqual:
    case InvalidCustomKey(name: String)

    def message: String = this match
      case InvalidCustomKey(name) =>
        s"Custom attribute name '$name' must match ${BinaryAttributes.customKeyPattern}"

  private val customKeyPattern = "^[A-Za-z0-9][A-Za-z0-9._:-]{0,63}$"

  object BinaryAttributesRow:
    import kyo.Record.`~`

    type Fields =
      "size" ~ Option[Tracked[FileSize]] & "chunkCount" ~ Option[Tracked[ChunkCount]] & "mime" ~ Option[Tracked[Mime]] &
        "digests" ~ Map[Algo, Tracked[HexLower]] & "custom" ~ Map[String, Tracked[String]]

    type Record = kyo.Record[Fields]

    val empty: Record =
      apply(
        size = None,
        chunkCount = None,
        mime = None,
        digests = Map.empty,
        custom = Map.empty,
      )

    def apply(
      size: Option[Tracked[FileSize]],
      chunkCount: Option[Tracked[ChunkCount]],
      mime: Option[Tracked[Mime]],
      digests: Map[Algo, Tracked[HexLower]],
      custom: Map[String, Tracked[String]],
    ): Record =
      ("size" ~ size) &
        ("chunkCount" ~ chunkCount) &
        ("mime" ~ mime) &
        ("digests" ~ digests) &
        ("custom" ~ custom)

    def update(
      record: Record
    )(
      size: Option[Tracked[FileSize]] = size(record),
      chunkCount: Option[Tracked[ChunkCount]] = chunkCount(record),
      mime: Option[Tracked[Mime]] = mime(record),
      digests: Map[Algo, Tracked[HexLower]] = digests(record),
      custom: Map[String, Tracked[String]] = custom(record),
    ): Record =
      apply(size, chunkCount, mime, digests, custom)

    def size(record: Record): Option[Tracked[FileSize]] =
      record.selectDynamic("size").asInstanceOf[Option[Tracked[FileSize]]]

    def chunkCount(record: Record): Option[Tracked[ChunkCount]] =
      record.selectDynamic("chunkCount").asInstanceOf[Option[Tracked[ChunkCount]]]

    def mime(record: Record): Option[Tracked[Mime]] =
      record.selectDynamic("mime").asInstanceOf[Option[Tracked[Mime]]]

    def digests(record: Record): Map[Algo, Tracked[HexLower]] =
      record.selectDynamic("digests").asInstanceOf[Map[Algo, Tracked[HexLower]]]

    def custom(record: Record): Map[String, Tracked[String]] =
      record.selectDynamic("custom").asInstanceOf[Map[String, Tracked[String]]]
  end BinaryAttributesRow

  private[attributes] def isValidCustomKey(name: String): Boolean =
    name.nonEmpty && name.length <= 64 && name.matches(customKeyPattern)

final case class BinaryAttributes private (
  advertised: BinaryAttributes.BinaryAttributesRow.Record = BinaryAttributes.BinaryAttributesRow.empty,
  confirmed: BinaryAttributes.BinaryAttributesRow.Record = BinaryAttributes.BinaryAttributesRow.empty,
  history: Vector[(String, Instant)] = Vector.empty,
):
  import BinaryAttributes.*

  def record(event: String): BinaryAttributes =
    copy(history = history :+ (event -> Instant.now()))

  def advertise[A](key: BinaryAttributeKey[A], value: Tracked[A]): BinaryAttributes =
    copy(advertised = write(advertised, key, value))

  def confirm[A](key: BinaryAttributeKey[A], value: Tracked[A]): BinaryAttributes =
    copy(confirmed = write(confirmed, key, value))

  def advertiseSize(value: Tracked[FileSize]): BinaryAttributes =
    advertise(BinaryAttributeKey.Size, value)

  def confirmSize(value: Tracked[FileSize]): BinaryAttributes =
    confirm(BinaryAttributeKey.Size, value)

  def advertiseChunkCount(value: Tracked[ChunkCount]): BinaryAttributes =
    advertise(BinaryAttributeKey.ChunkCount, value)

  def confirmChunkCount(value: Tracked[ChunkCount]): BinaryAttributes =
    confirm(BinaryAttributeKey.ChunkCount, value)

  def advertiseMime(value: Tracked[Mime]): BinaryAttributes =
    advertise(BinaryAttributeKey.Mime, value)

  def confirmMime(value: Tracked[Mime]): BinaryAttributes =
    confirm(BinaryAttributeKey.Mime, value)

  def advertiseDigest(algo: Algo, value: Tracked[HexLower]): BinaryAttributes =
    advertise(BinaryAttributeKey.Digest(algo), value)

  def confirmDigest(algo: Algo, value: Tracked[HexLower]): BinaryAttributes =
    confirm(BinaryAttributeKey.Digest(algo), value)

  def advertiseCustom(name: String, value: Tracked[String]): BinaryAttributes =
    advertise(BinaryAttributeKey.Custom(name), value)

  def confirmCustom(name: String, value: Tracked[String]): BinaryAttributes =
    confirm(BinaryAttributeKey.Custom(name), value)

  def get[A](key: BinaryAttributeKey[A]): Option[Tracked[A]] =
    confirmedValue(key).orElse(advertisedValue(key))

  def confirmedValue[A](key: BinaryAttributeKey[A]): Option[Tracked[A]] =
    read(confirmed, key)

  def advertisedValue[A](key: BinaryAttributeKey[A]): Option[Tracked[A]] =
    read(advertised, key)

  def size: Option[Tracked[FileSize]] = get(BinaryAttributeKey.Size)

  def chunkCount: Option[Tracked[ChunkCount]] = get(BinaryAttributeKey.ChunkCount)

  def mime: Option[Tracked[Mime]] = get(BinaryAttributeKey.Mime)

  def digest(algo: Algo): Option[Tracked[HexLower]] =
    get(BinaryAttributeKey.Digest(algo))

  def advertisedEntries: ListMap[BinaryAttributeKey[?], Tracked[?]] =
    materialize(advertised)

  def confirmedEntries: ListMap[BinaryAttributeKey[?], Tracked[?]] =
    materialize(confirmed)

  def validate: Either[ValidationError, BinaryAttributes] =
    (BinaryAttributesRow.custom(advertised).keysIterator ++ BinaryAttributesRow.custom(confirmed).keysIterator)
      .collectFirst {
        case name if !isValidCustomKey(name) =>
          ValidationError.InvalidCustomKey(name)
      }
      .map(Left(_))
      .getOrElse(Right(this))

  private def write[A](
    record: BinaryAttributesRow.Record,
    key: BinaryAttributeKey[A],
    value: Tracked[A],
  ): BinaryAttributesRow.Record =
    key match
      case BinaryAttributeKey.Size         =>
        BinaryAttributesRow.update(record)(size = mergeTracked(BinaryAttributesRow.size(record), value))
      case BinaryAttributeKey.ChunkCount   =>
        BinaryAttributesRow.update(record)(chunkCount = mergeTracked(BinaryAttributesRow.chunkCount(record), value))
      case BinaryAttributeKey.Mime         =>
        BinaryAttributesRow.update(record)(mime = mergeTracked(BinaryAttributesRow.mime(record), value))
      case BinaryAttributeKey.Digest(algo) =>
        val merged = mergeEntry(BinaryAttributesRow.digests(record), algo, value)
        BinaryAttributesRow.update(record)(digests = merged)
      case BinaryAttributeKey.Custom(name) =>
        val merged = mergeEntry(BinaryAttributesRow.custom(record), name, value)
        BinaryAttributesRow.update(record)(custom = merged)

  private def read[A](record: BinaryAttributesRow.Record, key: BinaryAttributeKey[A]): Option[Tracked[A]] =
    key match
      case BinaryAttributeKey.Size         => BinaryAttributesRow.size(record)
      case BinaryAttributeKey.ChunkCount   => BinaryAttributesRow.chunkCount(record)
      case BinaryAttributeKey.Mime         => BinaryAttributesRow.mime(record)
      case BinaryAttributeKey.Digest(algo) =>
        BinaryAttributesRow.digests(record).get(algo)
      case BinaryAttributeKey.Custom(name) =>
        BinaryAttributesRow.custom(record).get(name)

  private def materialize(record: BinaryAttributesRow.Record): ListMap[BinaryAttributeKey[?], Tracked[?]] =
    import scala.collection.mutable.ListBuffer
    val buffer = ListBuffer.empty[(BinaryAttributeKey[?], Tracked[?])]
    BinaryAttributesRow.size(record).foreach(value => buffer += (BinaryAttributeKey.Size -> value))
    BinaryAttributesRow.chunkCount(record).foreach(value => buffer += (BinaryAttributeKey.ChunkCount -> value))
    BinaryAttributesRow.mime(record).foreach(value => buffer += (BinaryAttributeKey.Mime -> value))
    BinaryAttributesRow.digests(record).foreach { case (algo, value) =>
      buffer += (BinaryAttributeKey.Digest(algo) -> value)
    }
    BinaryAttributesRow.custom(record).foreach { case (name, value) =>
      buffer += (BinaryAttributeKey.Custom(name) -> value)
    }
    ListMap.from(buffer)

  private def mergeTracked[A](current: Option[Tracked[A]], next: Tracked[A]): Option[Tracked[A]] =
    current match
      case Some(existing) => Some(Tracked.merge(existing, next))
      case None           => Some(next)

  private def mergeEntry[K, A](entries: Map[K, Tracked[A]], key: K, next: Tracked[A]): Map[K, Tracked[A]] =
    entries.updatedWith(key) {
      case Some(existing) => Some(Tracked.merge(existing, next))
      case None           => Some(next)
    }

final case class BlobWriteResult(
  key: BinaryKey,
  locator: BlobLocator,
  attributes: BinaryAttributes,
)

object Source:
  given zio.schema.Schema[Source] = DeriveSchema.gen[Source]
