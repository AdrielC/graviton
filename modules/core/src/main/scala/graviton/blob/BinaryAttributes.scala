package graviton.blob

import graviton.blob.Types.*
import io.github.iltotore.iron.zioJson.given
import _root_.zio.json.{DeriveJsonCodec, DeriveJsonDecoder, DeriveJsonEncoder, JsonCodec, JsonDecoder, JsonEncoder}

import java.time.Instant

enum Source:
  case Sniffed, Derived, ProvidedUser, ProvidedSystem, Verified

final case class Tracked[+A](
  value: A,
  source: Source,
  at: Instant,
  note: Option[String] = None,
)

object Tracked:
  given [A: JsonEncoder]: JsonEncoder[Tracked[A]]                                          = DeriveJsonEncoder.gen[Tracked[A]]
  given [A: JsonDecoder]: JsonDecoder[Tracked[A]]                                          = DeriveJsonDecoder.gen[Tracked[A]]
  given [A](using JsonEncoder[Tracked[A]], JsonDecoder[Tracked[A]]): JsonCodec[Tracked[A]] =
    JsonCodec(implicitly, implicitly)
  def now[A](value: A, source: Source, note: Option[String] = None): Tracked[A]            =
    Tracked(value, source, Instant.now(), note)

  def merge[A](left: Tracked[A], right: Tracked[A]): Tracked[A] =
    val precedence: Source => Int =
      case Source.Verified       => 5
      case Source.ProvidedUser   => 4
      case Source.ProvidedSystem => 3
      case Source.Derived        => 2
      case Source.Sniffed        => 1
    val leftRank                  = precedence(left.source)
    val rightRank                 = precedence(right.source)
    if leftRank > rightRank then left
    else if rightRank > leftRank then right
    else if left.at.isAfter(right.at) then left
    else right

final case class BinaryAttributes(
  size: Option[Tracked[Size]] = None,
  chunkCount: Option[Tracked[ChunkCount]] = None,
  mime: Option[Tracked[Mime]] = None,
  digests: Map[Algo, Tracked[HexLower]] = Map.empty,
  extra: Map[String, Tracked[String]] = Map.empty,
  history: Vector[(String, Instant)] = Vector.empty,
):
  def record(event: String): BinaryAttributes =
    copy(history = history :+ (event -> Instant.now()))

  def upsertSize(value: Tracked[Size]): BinaryAttributes =
    copy(size = Some(size.fold(value)(Tracked.merge(_, value))))

  def upsertChunkCount(value: Tracked[ChunkCount]): BinaryAttributes =
    copy(chunkCount = Some(chunkCount.fold(value)(Tracked.merge(_, value))))

  def upsertMime(value: Tracked[Mime]): BinaryAttributes =
    copy(mime = Some(mime.fold(value)(Tracked.merge(_, value))))

  def upsertDigest(algo: Algo, value: Tracked[HexLower]): BinaryAttributes =
    val merged = digests.get(algo).fold(value)(Tracked.merge(_, value))
    copy(digests = digests.updated(algo, merged))

  def upsertExtra(key: String, value: Tracked[String]): BinaryAttributes =
    val merged = extra.get(key).fold(value)(Tracked.merge(_, value))
    copy(extra = extra.updated(key, merged))

  def diff(that: BinaryAttributes): BinaryAttributesDiff =
    BinaryAttributesDiff.from(this, that)

object BinaryAttributes:
  given JsonCodec[BinaryAttributes] = DeriveJsonCodec.gen[BinaryAttributes]
  val empty: BinaryAttributes       = BinaryAttributes()

final case class BinaryAttributesDiff(
  size: Option[(Option[Tracked[Size]], Option[Tracked[Size]])],
  chunkCount: Option[(Option[Tracked[ChunkCount]], Option[Tracked[ChunkCount]])],
  mime: Option[(Option[Tracked[Mime]], Option[Tracked[Mime]])],
  digests: Map[Algo, (Option[Tracked[HexLower]], Option[Tracked[HexLower]])],
  extra: Map[String, (Option[Tracked[String]], Option[Tracked[String]])],
)

object BinaryAttributesDiff:
  given JsonCodec[BinaryAttributesDiff]                                           = DeriveJsonCodec.gen[BinaryAttributesDiff]
  def from(left: BinaryAttributes, right: BinaryAttributes): BinaryAttributesDiff =
    val digestKeys = left.digests.keySet union right.digests.keySet
    val extraKeys  = left.extra.keySet union right.extra.keySet

    val digestDiffs: Map[Algo, (Option[Tracked[HexLower]], Option[Tracked[HexLower]])] =
      digestKeys.flatMap { key =>
        val pair = (left.digests.get(key), right.digests.get(key))
        Option.when(pair._1 != pair._2)((key, pair))
      }.toMap

    val extraDiffs: Map[String, (Option[Tracked[String]], Option[Tracked[String]])] =
      extraKeys.flatMap { key =>
        val pair = (left.extra.get(key), right.extra.get(key))
        Option.when(pair._1 != pair._2)((key, pair))
      }.toMap

    BinaryAttributesDiff(
      size = Option.when(left.size != right.size)((left.size, right.size)),
      chunkCount = Option.when(left.chunkCount != right.chunkCount)((left.chunkCount, right.chunkCount)),
      mime = Option.when(left.mime != right.mime)((left.mime, right.mime)),
      digests = digestDiffs,
      extra = extraDiffs,
    )

final case class BlobWriteResult(
  key: BinaryKey,
  locator: BlobLocator,
  attributes: BinaryAttributes,
)

object BlobWriteResult:
  given JsonCodec[BlobWriteResult] = DeriveJsonCodec.gen[BlobWriteResult]

object Source:
  given JsonCodec[Source] = DeriveJsonCodec.gen[Source]
