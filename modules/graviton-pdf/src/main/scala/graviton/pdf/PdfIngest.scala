package graviton.pdf

import graviton.core.attributes.BinaryAttributes
import graviton.core.types.Mime
import graviton.runtime.model.{BlobWritePlan, BlobWriteResult}
import graviton.runtime.stores.{BlobStore, StoreError}
import graviton.runtime.upload.{UploadIngestor, UploadIntent}
import graviton.shared.MediaTypeText
import zio.{IO, ZIO}
import zio.blocks.mediatype.MediaType
import zio.pdf.PdfMime
import zio.stream.ZStream

/** Typed, streaming entry point for storing an advertised PDF in Graviton. */
object PdfIngest:

  sealed abstract class Error(message: String) extends IllegalArgumentException(message)

  final case class UnsupportedMediaType(advertised: MediaType)
      extends Error(s"PDF ingest requires ${PdfMime.mimeType.fullType}, received ${advertised.fullType}")

  /** Retained from 0.4.0 for binary and pattern-match compatibility. */
  final case class ConflictingMimeMetadata(existing: Mime) extends Error(s"PDF ingest plan already declares ${existing.value}")

  final case class InvalidMediaTypeMetadata(reason: String) extends Error(s"Invalid PDF media type metadata: $reason")

  def accepts(advertised: MediaType): Boolean =
    advertised != null &&
      PdfMime.mimeType.mainType.equalsIgnoreCase(advertised.mainType) &&
      PdfMime.mimeType.subType.equalsIgnoreCase(advertised.subType)

  def put(
    store: BlobStore,
    advertised: MediaType,
    bytes: ZStream[Any, Throwable, Byte],
    plan: BlobWritePlan = BlobWritePlan(),
    config: PdfAwareChunker.Config = PdfAwareChunker.Config.default,
  ): IO[Throwable, BlobWriteResult] =
    val prepared: Either[Error, BinaryAttributes] =
      for
        rendered  <- MediaTypeText.renderEither(advertised).left.map(InvalidMediaTypeMetadata.apply)
        canonical <- MediaTypeText.parse(rendered).left.map(InvalidMediaTypeMetadata.apply)
        _         <- Either.cond(accepts(canonical), (), UnsupportedMediaType(canonical))
        _         <- plan.attributes.mime match
                       case Some(existing) =>
                         MediaTypeText.parse(existing.value) match
                           case Right(value) if MediaTypeText.renderEither(value).contains(rendered) => Right(())
                           case _                                                                    =>
                             Left(ConflictingMimeMetadata(existing))
                       case None           => Right(())
        next      <- plan.attributes.advertiseMediaType(canonical).left.map(InvalidMediaTypeMetadata.apply)
        confirmed <- next.confirmMediaType(canonical).left.map(InvalidMediaTypeMetadata.apply)
      yield confirmed

    ZIO.fromEither(prepared).flatMap { attributes =>
      PdfUploadSupport
        .ingestor(store, config = config)
        .put(
          UploadIntent(advertised, expectedSize = None),
          bytes,
          plan.copy(attributes = attributes),
        )
        .map(_.stored)
        .mapError {
          case UploadIngestor.Error.Storage(cause: StoreError.InvalidInput) => cause
          case error                                                        => error
        }
    }

end PdfIngest
