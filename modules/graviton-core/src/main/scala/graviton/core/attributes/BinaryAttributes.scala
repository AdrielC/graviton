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

  private type AttrMap = ListMap[BinaryAttributeKey[?], BinaryAttributeValue[?]]

  private val customKeyPattern = "^[A-Za-z0-9][A-Za-z0-9._:-]{0,63}$"

  private[attributes] def write[A](
    map: AttrMap,
    key: BinaryAttributeKey[A],
    value: Tracked[A],
  ): AttrMap =
    map.get(key) match
      case Some(existing) =>
        map.updated(key, existing.asInstanceOf[BinaryAttributeValue[A]].merge(value))
      case None           =>
        map.updated(key, BinaryAttributeValue(value))

  private[attributes] def read[A](map: AttrMap, key: BinaryAttributeKey[A]): Option[Tracked[A]] =
    map.get(key).map(_.asInstanceOf[BinaryAttributeValue[A]].tracked)

  private[attributes] def materialize(map: AttrMap): ListMap[BinaryAttributeKey[?], Tracked[?]] =
    ListMap.from(map.iterator.map { case (key, entry) => key -> entry.tracked })

  private[attributes] def isValidCustomKey(name: String): Boolean =
    name.nonEmpty && name.length <= 64 && name.matches(customKeyPattern)

private final case class BinaryAttributeValue[A](tracked: Tracked[A]):
  def merge(next: Tracked[A]): BinaryAttributeValue[A] =
    BinaryAttributeValue(Tracked.merge(tracked, next))

final case class BinaryAttributes private (
  advertised: BinaryAttributes.AttrMap = ListMap.empty,
  confirmed: BinaryAttributes.AttrMap = ListMap.empty,
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
    (advertised.keysIterator ++ confirmed.keysIterator)
      .collectFirst {
        case BinaryAttributeKey.Custom(name) if !isValidCustomKey(name) =>
          ValidationError.InvalidCustomKey(name)
      }
      .map(Left(_))
      .getOrElse(Right(this))

final case class BlobWriteResult(
  key: BinaryKey,
  locator: BlobLocator,
  attributes: BinaryAttributes,
)

object Source:
  given zio.schema.Schema[Source] = DeriveSchema.gen[Source]
