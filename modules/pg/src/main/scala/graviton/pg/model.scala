package graviton.pg

import zio.*
import zio.Chunk
import zio.json.*
import zio.json.ast.Json
import com.augustnagro.magnum.*
import com.augustnagro.magnum.pg.json.*
import io.github.iltotore.iron.{zio as _, *}
import io.github.iltotore.iron.constraint.all.*
import zio.schema.Schema
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


type HashBytes  = Chunk[Byte] :| (MinLength[16] & MaxLength[64])
type SmallBytes = Chunk[Byte] :| MaxLength[1048576]

type ExactLength[N <: Int] = Length[StrictEqual[N]]

type StoreKey = StoreKey.T
object StoreKey extends RefinedTypeExt[Chunk[Byte], ExactLength[32]]

type PosLong    = Long :| Positive
type NonNegLong = Long :| Not[Negative]

object Algo:
  enum Id derives CanEqual, DbCodec:
    case Blake3, Sha256, Sha1, Md5


enum StoreStatus derives CanEqual, DbCodec:
  @SqlName("active") case Active
  @SqlName("paused") case Paused
  @SqlName("retired") case Retired

enum LocationStatus derives CanEqual, DbCodec:
  @SqlName("active") case Active
  @SqlName("stale") case Stale
  @SqlName("missing") case Missing
  @SqlName("deprecated") case Deprecated
  @SqlName("error") case Error

final case class BlockKey(algoId: Short, hash: HashBytes) derives DbCodec

final case class FileKey(
  algoId: Short,
  hash: HashBytes,
  size: PosLong,
  mediaType: Option[String],
) derives DbCodec

final case class BlobStoreRow(
  key: StoreKey,
  implId: String,
  buildFp: Chunk[Byte],
  dvSchemaUrn: String,
  dvCanonical: Chunk[Byte],
  dvJsonPreview: Option[Json],
  status: StoreStatus,
  version: Long,
) derives DbCodec

// ---- DbCodec instances for refined types

given DbCodec[Chunk[Byte]] =
  DbCodec[Array[Byte]].biMap(Chunk.fromArray, _.toArray)

inline given [T, C](using DbCodec[T], Constraint[T, C]): DbCodec[T :| C] =
  summon[DbCodec[T]].biMap(_.refineUnsafe[C], identity)

given DbCodec[java.util.UUID] =
  DbCodec[String].biMap(java.util.UUID.fromString, _.toString)

given JsonBDbCodec[Json] with
  def encode(a: Json): String    =
    a.toJson
  def decode(json: String): Json =
    json
      .fromJson[Json]
      .fold(err => throw IllegalArgumentException(err), identity)