package graviton.pdf

import graviton.core.attributes.BinaryAttributes
import graviton.core.types.Mime
import graviton.runtime.model.{BlobWritePlan, BlobWriteResult}
import graviton.runtime.stores.BlobStore
import graviton.streams.Chunker
import zio.{IO, ZIO}
import zio.blocks.mediatype.MediaType
import zio.pdf.PdfMime
import zio.stream.ZStream

/** Typed, streaming entry point for storing an advertised PDF in Graviton. */
object PdfIngest:

  sealed abstract class Error(message: String) extends IllegalArgumentException(message)

  final case class UnsupportedMediaType(advertised: MediaType)
      extends Error(s"PDF ingest requires ${PdfMime.mimeType.fullType}, received ${advertised.fullType}")

  final case class ConflictingMimeMetadata(existing: Mime) extends Error(s"PDF ingest plan already declares ${existing.value}")

  private val ConfirmedPdfMime: Mime =
    // SAFETY: PdfMime is the IANA application/pdf constant from zio-pdf.
    Mime.applyUnsafe(PdfMime.mimeType.fullType)

  def accepts(advertised: MediaType): Boolean =
    PdfMime.mimeType.matches(advertised, ignoreParameters = true)

  def put(
    store: BlobStore,
    advertised: MediaType,
    bytes: ZStream[Any, Throwable, Byte],
    plan: BlobWritePlan = BlobWritePlan(),
    config: PdfAwareChunker.Config = PdfAwareChunker.Config.default,
  ): IO[Throwable, BlobWriteResult] =
    if !accepts(advertised) then ZIO.fail(UnsupportedMediaType(advertised))
    else
      plan.attributes.mime match
        case Some(existing) if existing.value != ConfirmedPdfMime.value =>
          ZIO.fail(ConflictingMimeMetadata(existing))
        case _                                                          =>
          val attributes: BinaryAttributes =
            plan.attributes
              .advertiseMime(ConfirmedPdfMime)
              .confirmMime(ConfirmedPdfMime)

          Chunker.locally(PdfAwareChunker(config)) {
            bytes.run(store.put(plan.copy(attributes = attributes)))
          }

end PdfIngest
