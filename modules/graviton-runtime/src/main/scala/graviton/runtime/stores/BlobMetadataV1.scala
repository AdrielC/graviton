package graviton.runtime.stores

import graviton.core.attributes.BinaryAttributes
import graviton.core.types.Mime
import graviton.shared.{ApiJson, ApiJsonCodec, MediaTypeText}
import zio.Chunk
import zio.blocks.mediatype.MediaType
import zio.blocks.schema.Schema

import java.nio.charset.StandardCharsets

/**
 * Bounded, versioned semantic metadata persisted atomically with a manifest.
 *
 * Blob payloads and manifest entries never pass through this codec. The JSON
 * envelope is intentionally limited to small control-plane values so ZIO
 * Blocks can provide one schema-derived contract without materializing data
 * plane bytes.
 */
final case class BlobMetadataV1 private (
  mediaType: Option[Mime],
  chunker: ManifestChunkerId,
):
  def canonicalMediaType: String = mediaType.fold(BlobMetadataV1.DefaultMediaType)(_.value)

  def parsedMediaType: Either[String, MediaType] = MediaTypeText.parse(canonicalMediaType)

object BlobMetadataV1:
  val SchemaId: String         = "graviton.blob-metadata"
  val SchemaVersion: Int       = 1
  val CodecVersion: Int        = 1
  val MaxEncodedBytes: Int     = 1024
  val DefaultMediaType: String = "application/octet-stream"

  private final case class Wire(
    schemaId: String,
    schemaVersion: Int,
    codecVersion: Int,
    mediaType: Option[String],
    chunker: String,
  )

  private object Wire:
    given Schema[Wire] = Schema.derived

  given ApiJsonCodec[BlobMetadataV1] =
    ApiJsonCodec.mapped[BlobMetadataV1, Wire](value =>
      Wire(
        schemaId = SchemaId,
        schemaVersion = SchemaVersion,
        codecVersion = CodecVersion,
        mediaType = value.mediaType.map(_.value),
        chunker = value.chunker.value,
      )
    )(wire =>
      for
        _         <- Either.cond(wire.schemaId == SchemaId, (), s"unsupported blob metadata schema '${wire.schemaId}'")
        _         <- Either.cond(
                       wire.schemaVersion == SchemaVersion,
                       (),
                       s"unsupported blob metadata schema version ${wire.schemaVersion}",
                     )
        _         <- Either.cond(
                       wire.codecVersion == CodecVersion,
                       (),
                       s"unsupported blob metadata codec version ${wire.codecVersion}",
                     )
        mediaType <- wire.mediaType match
                       case None        => Right(None)
                       case Some(value) =>
                         MediaTypeText
                           .parse(value)
                           .flatMap(MediaTypeText.renderEither)
                           .flatMap(Mime.either)
                           .map(Some(_))
        chunker   <- ManifestChunkerId.either(wire.chunker)
      yield BlobMetadataV1(mediaType, chunker)
    )

  def make(mediaType: Option[MediaType], chunker: ManifestChunkerId): Either[String, BlobMetadataV1] =
    mediaType match
      case None        => Right(BlobMetadataV1(None, chunker))
      case Some(value) =>
        MediaTypeText
          .renderEither(value)
          .flatMap(Mime.either)
          .map(mime => BlobMetadataV1(Some(mime), chunker))

  def fromAttributes(attributes: BinaryAttributes, chunker: ManifestChunkerId): Either[String, BlobMetadataV1] =
    attributes.mediaType.flatMap(make(_, chunker))

  def default(chunker: ManifestChunkerId): BlobMetadataV1 =
    BlobMetadataV1(None, chunker)

  def encode(value: BlobMetadataV1): Either[String, Chunk[Byte]] =
    val bytes = ApiJson.encode(value).getBytes(StandardCharsets.UTF_8)
    Either.cond(
      bytes.length <= MaxEncodedBytes,
      Chunk.fromArray(bytes),
      s"blob metadata exceeds the $MaxEncodedBytes-byte encoded limit",
    )

  def decode(bytes: Chunk[Byte]): Either[String, BlobMetadataV1] =
    if bytes.length > MaxEncodedBytes then Left(s"blob metadata exceeds the $MaxEncodedBytes-byte encoded limit")
    else ApiJson.decode[BlobMetadataV1](new String(bytes.toArray, StandardCharsets.UTF_8))
