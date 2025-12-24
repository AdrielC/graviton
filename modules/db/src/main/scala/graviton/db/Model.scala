package graviton.db

import io.github.iltotore.iron.RuntimeConstraint

import zio.*
import zio.Chunk
import zio.json.*
import zio.json.ast.Json
import com.augustnagro.magnum.*
import com.augustnagro.magnum.pg.json.*
import io.github.iltotore.iron.{zio as _, *}
import io.github.iltotore.iron.constraint.all.*
import zio.schema.*
import zio.schema.derived

import scala.unchecked

case class RefinedTypeExtMessage(message: String)

trait RefinedTypeExt[A: {Schema, DbCodec}, C] extends RefinedType[A, C]:

  given DbCodec[T] =
    summon[DbCodec[A]]
      .biMap(applyUnsafe(_), _.value)

  given Schema[T] =
    summon[Schema[A]]
      .transformOrFail(either(_), a => Right(a.value))
      .annotate((RefinedTypeExtMessage(rtc.message)))

end RefinedTypeExt

// inline given [A : DbCodec, C] => (inline rtc: Constraint[A, C]) => DbCodec[IronType[A, C]] =
//   summon[DbCodec[A]].biMap(_.refineUnsafe[C], a => (a: A :| C))

type HashBytes = HashBytes.T
object HashBytes extends RefinedTypeExt[Chunk[Byte], MinLength[16] & MaxLength[64]]

type SmallBytes = SmallBytes.T
object SmallBytes extends RefinedTypeExt[Chunk[Byte], MaxLength[1048576]]

type ExactLength[N <: Int] = Length[StrictEqual[N]]

type StoreKey = StoreKey.T
object StoreKey extends RefinedTypeExt[Chunk[Byte], ExactLength[32]]

type PosLong = PosLong.T
object PosLong extends RefinedTypeExt[Long, Positive]

type NonNegLong = NonNegLong.T
object NonNegLong extends RefinedTypeExt[Long, Not[Negative]]

object Algo:
  enum Id derives CanEqual:
    case Blake3, Sha256, Sha1, Md5

enum StoreStatus derives CanEqual:
  case Active, Paused, Retired

object StoreStatus:
  private val toPg: Map[StoreStatus, String] = Map(
    Active  -> "active",
    Paused  -> "paused",
    Retired -> "retired",
  )

  private val fromPg: Map[String, StoreStatus] = toPg.map(_.swap)

  given DbCodec[StoreStatus] =
    DbCodec[String].biMap(
      str =>
        fromPg.getOrElse(
          str,
          throw IllegalArgumentException(s"Unknown store_status_t value '$str'"),
        ),
      status => toPg(status),
    )

  given Schema[StoreStatus] =
    Schema[String]
      .transform(
        str => fromPg.getOrElse(str, throw IllegalArgumentException("Unknown store_status_t value")),
        toPg,
      )

enum ReplicaStatus derives CanEqual:
  case Active, Quarantined, Deprecated, Lost

object ReplicaStatus:
  private val toPg: Map[ReplicaStatus, String] = Map(
    Active      -> "active",
    Quarantined -> "quarantined",
    Deprecated  -> "deprecated",
    Lost        -> "lost",
  )

  private val fromPg: Map[String, ReplicaStatus] = toPg.map(_.swap)

  given DbCodec[ReplicaStatus] =
    DbCodec[String].biMap(
      str =>
        fromPg.getOrElse(
          str,
          throw IllegalArgumentException(s"Unknown replica_status_t value '$str'"),
        ),
      status => toPg(status),
    )

  given Schema[ReplicaStatus] =
    Schema[String]
      .transform(
        str => fromPg.getOrElse(str, throw IllegalArgumentException("Unknown replica_status_t value")),
        toPg,
      )

final case class BlockInsert(
  algoId: Short,
  hash: HashBytes,
  size: PosLong,
  offset: NonNegLong,
) derives DbCodec,
      Schema

final case class BlockKey(algoId: Short, hash: HashBytes) derives DbCodec, Schema

final case class BlobKey(
  algoId: Short,
  hash: HashBytes,
  size: NonNegLong,
  mediaTypeHint: Option[String],
) derives DbCodec,
      Schema

final case class StoreRow(
  key: StoreKey,
  implId: String,
  buildFp: Chunk[Byte],
  dvSchemaUrn: String,
  dvCanonical: Chunk[Byte],
  dvJsonPreview: Option[Json],
  status: StoreStatus,
  version: Long,
) derives DbCodec,
      Schema

// ---- DbCodec instances for refined types

given DbCodec[Chunk[Byte]] =
  DbCodec[Array[Byte]].biMap(Chunk.fromArray, _.toArray)

final case class DbRange[+T](lower: Option[T], upper: Option[T]) derives DbCodec, Schema

given JsonBDbCodec[Json] with
  def encode(a: Json): String    = a.toJson
  def decode(json: String): Json = json
    .fromJson[Json]
    .fold(err => throw IllegalArgumentException(err), identity)
