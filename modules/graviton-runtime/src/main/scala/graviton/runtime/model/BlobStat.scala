package graviton.runtime.model

import graviton.core.model.ByteConstraints
import graviton.core.types.FileSize
import java.time.Instant
import zio.Chunk
import zio.schema.{Schema, TypeId}
import zio.schema.validation.Validation
import graviton.core.bytes.Digest

final case class BlobStat(size: FileSize, digest: Digest, lastModified: Instant)

object BlobStat:
  private val fileSizeSchema: Schema[FileSize] =
    Schema[Long].transformOrFail(
      value => ByteConstraints.refineFileSize(value),
      refined => Right(refined.asInstanceOf[Long]),
    )

  private val sizeField: Schema.Field[BlobStat, FileSize] =
    Schema.Field(
      name0 = "size",
      schema0 = fileSizeSchema,
      annotations0 = Chunk.empty,
      validation0 = Validation.succeed,
      get0 = _.size,
      set0 = (blob, value) => blob.copy(size = value),
    )

  private val digestField: Schema.Field[BlobStat, Digest] =
    Schema.Field(
      name0 = "digest",
      schema0 = Schema[Digest],
      annotations0 = Chunk.empty,
      validation0 = Validation.succeed,
      get0 = _.digest,
      set0 = (blob, value) => blob.copy(digest = value),
    )

  private val lastModifiedField: Schema.Field[BlobStat, Instant] =
    Schema.Field(
      name0 = "lastModified",
      schema0 = Schema[Instant],
      annotations0 = Chunk.empty,
      validation0 = Validation.succeed,
      get0 = _.lastModified,
      set0 = (blob, value) => blob.copy(lastModified = value),
    )

  given Schema[BlobStat] =
    Schema.CaseClass3(
      id0 = TypeId.Structural,
      field01 = sizeField,
      field02 = digestField,
      field03 = lastModifiedField,
      construct0 = (size, etag, modified) => BlobStat(size, etag, modified),
      annotations0 = Chunk.empty,
    )
