package graviton.pdf

import graviton.core.types.Identifier
import graviton.runtime.stores.BlobStore
import graviton.runtime.upload.*
import graviton.runtime.upload.UploadProbe.*
import zio.*
import zio.pdf.PdfMime

/** PDF detector and per-upload chunker provider for the generic upload runtime. */
object PdfUploadSupport:
  private val PdfKey =
    UploadMediaTypeKey
      .from(PdfMime.mimeType)
      .fold(message => throw new IllegalStateException(message), identity)

  private val Signature: Chunk[Byte] = Chunk('%'.toByte, 'P'.toByte, 'D'.toByte, 'F'.toByte, '-'.toByte)

  val detector: UploadMediaTypeDetector =
    UploadMediaTypeDetector.makeTyped(
      detectorId = Identifier.applyUnsafe("pdf-signature"),
      requiredBytes = UploadProbeSize.applyUnsafe(Signature.length),
      supportedTypes = Set(PdfKey),
      mismatch = advertised => s"advertised ${advertised.fullType} bytes do not start with %PDF-",
    ) { probe =>
      ZIO.succeed(Option.when(probe.bytes.startsWith(Signature))(PdfMime.mimeType))
    }

  def provider(config: PdfAwareChunker.Config = PdfAwareChunker.Config.default): ChunkerProvider =
    ChunkerProvider.makeTyped(ChunkerProviderId.applyUnsafe("pdf-object"))(_ => ZIO.succeed(PdfAwareChunker(config)))

  def providers(
    fallback: ChunkerProvider = ChunkerProvider.current,
    config: PdfAwareChunker.Config = PdfAwareChunker.Config.default,
  ): Map[ChunkerProvider.Key, ChunkerProvider]           =
    Map(
      ChunkerProvider.Key.Default           -> fallback,
      ChunkerProvider.Key.MediaType(PdfKey) -> provider(config),
    )

  def ingestor(
    store: BlobStore,
    fallback: ChunkerProvider = ChunkerProvider.current,
    config: PdfAwareChunker.Config = PdfAwareChunker.Config.default,
  ): UploadIngestor =
    UploadIngestor.make(store, Chunk.single(detector), providers(fallback, config))

  def layer(
    fallback: ChunkerProvider = ChunkerProvider.current,
    config: PdfAwareChunker.Config = PdfAwareChunker.Config.default,
  ): ZLayer[BlobStore, Nothing, UploadIngestor] =
    UploadIngestor.layer(Chunk.single(detector), providers(fallback, config))
