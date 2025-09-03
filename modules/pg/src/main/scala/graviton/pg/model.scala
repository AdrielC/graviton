package graviton.pg

import zio.*
import zio.Chunk
import com.augustnagro.magnum.DbCodec

// ---- Refined byte types

type HashBytes = Chunk[Byte]
type SmallBytes = Chunk[Byte]
type StoreKey = Chunk[Byte]

type PosLong = Long
type NonNegLong = Long

object Algo:
  enum Id derives CanEqual, DbCodec:
    case Blake3, Sha256, Sha1, Md5

final case class BlockKey(algoId: Short, hash: HashBytes) derives DbCodec

final case class FileKey(
    algoId: Short,
    hash: HashBytes,
    size: PosLong,
    mediaType: Option[String]
) derives DbCodec

final case class BlobStoreRow(
    key: StoreKey,
    implId: String,
    buildFp: Chunk[Byte],
    dvSchemaUrn: String,
    dvCanonical: Chunk[Byte],
    dvJsonPreview: Option[String],
    status: String,
    version: Long
) derives DbCodec

// ---- DbCodec instances for refined types

given DbCodec[Chunk[Byte]] =
  DbCodec[Array[Byte]].biMap(Chunk.fromArray, _.toArray)

given DbCodec[java.util.UUID] =
  DbCodec[String].biMap(java.util.UUID.fromString, _.toString)
