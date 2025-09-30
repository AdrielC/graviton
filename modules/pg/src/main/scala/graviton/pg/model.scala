package graviton.pg

import com.augustnagro.magnum.*
import com.augustnagro.magnum.pg.json.*
import graviton.db.*
import io.github.iltotore.iron.{zio as _, *}
import io.github.iltotore.iron.constraint.all.*
import zio.*
import zio.Chunk
import zio.json.*
import zio.json.ast.Json
export graviton.db.{
  BlockInsert,
  BlockKey,
  BlobKey,
  BlobStoreRow,
  BlobRepo,
  BlobStoreRepo,
  BlockRepo,
  Canon,
  Cursor,
  HashBytes,
  LocationStatus,
  Max,
  NonNegLong,
  PosLong,
  SmallBytes,
  StoreKey,
  StoreStatus,
}

given DbCodec[Chunk[Byte]] =
  DbCodec[Array[Byte]].biMap(Chunk.fromArray, _.toArray)

inline given [T, C](using DbCodec[T], Constraint[T, C]): DbCodec[T :| C] =
  summon[DbCodec[T]].biMap(_.refineUnsafe[C], _.asInstanceOf[T])

given DbCodec[java.util.UUID] =
  DbCodec[String].biMap(java.util.UUID.fromString, _.toString)

given DbCodec[PgRange[Long]] =
  DbCodec[String].biMap(
    s =>
      val stripped = s.stripPrefix("[").stripSuffix(")")
      val parts    = stripped.split(",", 2)
      val lower    = Option(parts.headOption.getOrElse(""))
        .filter(_.nonEmpty)
        .map(_.toLong)
      val upper    =
        if parts.length > 1 then Option(parts(1)).filter(_.nonEmpty).map(_.toLong) else None
      PgRange(lower, upper)
    ,
    r =>
      val lower = r.lower.map(_.toString).getOrElse("")
      val upper = r.upper.map(_.toString).getOrElse("")
      s"[$lower,$upper)",
  )

given DbCodec[StoreStatus] =
  DbCodec[String].biMap(StoreStatus.fromDbValue, _.dbValue)

given DbCodec[LocationStatus] =
  DbCodec[String].biMap(LocationStatus.fromDbValue, _.dbValue)

given DbCodec[StoreKey] =
  summon[DbCodec[Chunk[Byte]]].biMap(StoreKey.applyUnsafe(_), _.asInstanceOf[Chunk[Byte]])

given DbCodec[BlockKey] = DbCodec.derived

given DbCodec[BlobKey] = DbCodec.derived

given DbCodec[BlobStoreRow] = DbCodec.derived

given JsonBDbCodec[Json] with
  def encode(a: Json): String    =
    a.toJson
  def decode(json: String): Json =
    json
      .fromJson[Json]
      .fold(err => throw IllegalArgumentException(err), identity)
