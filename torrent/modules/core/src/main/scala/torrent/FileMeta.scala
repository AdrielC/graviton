package torrent

import java.time.Instant

import io.github.iltotore.iron.constraint.all.{ Match, Not }
import io.github.iltotore.iron.constraint.string.Blank
import io.github.iltotore.iron.{ DescribedAs, refineUnsafe, zio as _, * }
import torrent.BinaryKey.{ Scope, ScopePart }
import torrent.schemas.RefinedTypeExt

import zio.json.{ JsonDecoder, JsonEncoder }
import zio.schema.*
import zio.schema.syntax.*
import zio.{ Clock, Random, Runtime, UIO, Unsafe }

/**
 * Metadata about a stored file
 *
 * @param id
 *   Unique identifier for the file
 * @param fileName
 *   Original filename
 * @param contentType
 *   MIME type of the file
 * @param size
 *   Size in bytes
 * @param created
 *   Timestamp when the file was stored
 * @param checksum
 *   Optional checksum of the file content
 * @param attributes
 *   Additional custom metadata
 */
final case class FileMeta(
  id:          BinaryKey,
  fileName:    String :| Not[Blank],
  size:        Option[Long] = None,
  contentType: MediaType = MediaType.application.octetStream,
  created:     Option[Instant] = None,
  checksum:    Option[FileMeta.Checksum] = None,
  attributes:  BinaryAttributes = BinaryAttributes.empty
)

object FileMeta:

  // Regex that matches checksum string e.g. sha256:...
  inline val checksumRegex = """(?i)([a-z0-9]{1,30}]):([a-f0-9]{16,128})"""

  type Checksum = Checksum.T
  object Checksum
      extends RefinedTypeExt[
        String,
        DescribedAs[
          Match[FileMeta.checksumRegex.type],
          "Checksum of form <algorithm>:<hex-digest>"
        ]
      ]

  inline transparent given Schema[FileMeta]      = DeriveSchema.gen
  inline def codec: zio.json.JsonCodec[FileMeta] = zio.schema.codec.JsonCodec.jsonCodec(Schema[FileMeta])
  given JsonEncoder[FileMeta]                    = codec.encoder
  given JsonDecoder[FileMeta]                    = codec.decoder

  /**
   * Creates new metadata for a file about to be stored
   */
  def forUpload(fileName:    String :| Not[Blank],
                contentType: Option[MediaType] = None,
                size:        Option[Long] = None,
                attributes:  BinaryAttributes = BinaryAttributes.empty
  ): UIO[FileMeta] =
    Random.nextUUID.zipPar(Clock.instant).map { case (id, now) =>
      FileMeta(
        BinaryKey.Scoped(
          BinaryKey.UUID(id),
          Scope.either(Map(ScopePart("quasar://filename") -> Nil)).getOrElse(Scope.empty)
        ),
        fileName = fileName,
        contentType = contentType.getOrElse(MediaType.fromFileName(fileName)),
        size = size,
        created = Some(now),
        attributes = attributes
      )
    }

@main def printSchema() =
  println(FileMeta.given_Schema_FileMeta.serializable.ast)

  println(
    java.util.UUID.randomUUID().toString()
  )

  val meta1 = FileMeta(
    BinaryKey.Scoped(
      BinaryKey.UUID("c661789a-9d83-46f5-984d-e2b9f0eb7e16").toOption.get,
      Scope.either(Map(ScopePart("quasar://filename") -> Nil)).getOrElse(Scope.empty)
    ),
    fileName = "test.txt".refineUnsafe,
    size = Some(100),
    contentType = MediaType.text.plain
  )

  val meta2 = FileMeta(
    Unsafe.unsafe(implicit u => Runtime.default.unsafe.run(BinaryKey.random).getOrThrowFiberFailure()),
    fileName = "test12.txt".refineUnsafe,
    size = Some(400),
    contentType = MediaType.multipart.formData
  )

  println(meta1.diff(meta2))

  println(
    meta2.diff(meta1)
  )
