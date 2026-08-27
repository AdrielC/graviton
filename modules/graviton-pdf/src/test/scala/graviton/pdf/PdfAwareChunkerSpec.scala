package graviton.pdf

import graviton.core.attributes.BinaryAttributes
import graviton.core.model.Block
import graviton.core.types.{Mime, UploadChunkSize}
import graviton.runtime.Graviton
import graviton.runtime.model.BlobWritePlan
import graviton.streams.{BoundedByteStream, Chunker, ChunkerCore}
import zio.*
import zio.blocks.mediatype.{MediaType, MediaTypes}
import zio.pdf.PdfMime
import zio.stream.ZStream
import zio.test.*

import java.nio.charset.StandardCharsets

object PdfAwareChunkerSpec extends ZIOSpecDefault:

  private val ascii = StandardCharsets.US_ASCII

  private def bytes(value: String): Chunk[Byte] =
    Chunk.fromArray(value.getBytes(ascii))

  private val samplePdf: Chunk[Byte] =
    bytes(
      "%PDF-1.7\n" +
        "1 0 obj\n<</Type /Catalog /Pages 2 0 R>>\nendobj\n" +
        "2 0 obj\n<</Type /Pages /Count 1 /Kids [3 0 R]>>\nendobj\n" +
        "3 0 obj\n<</Type /Page /Parent 2 0 R /MediaBox [0 0 100 100]>>\nendobj\n" +
        "trailer\n<</Root 1 0 R>>\nstartxref\n0\n%%EOF\n"
    )

  private def config(
    policy: PdfAwareChunker.UnsupportedStructurePolicy = PdfAwareChunker.UnsupportedStructurePolicy.FixedSizeFallback
  ): PdfAwareChunker.Config =
    PdfAwareChunker.Config
      .make(
        targetBytes = UploadChunkSize.applyUnsafe(48),
        maxBytes = UploadChunkSize.applyUnsafe(128),
        maxCarryBytes = UploadChunkSize.applyUnsafe(256),
        unsupportedStructure = policy,
      )
      .fold(error => throw new IllegalArgumentException(error.message), identity)

  override def spec: Spec[TestEnvironment, Any] =
    suite("PdfAwareChunker")(
      test("uses zio-pdf object boundaries without changing bytes") {
        val chunker = PdfAwareChunker(config())

        for
          blocks               <- ZStream.fromChunk(samplePdf).rechunk(7).via(chunker.pipeline).runCollect
          restored              = blocks.flatMap(block => Block.bytes(block))
          completedObjectBlocks = blocks.dropRight(1).map { block =>
                                    new String(Block.bytes(block).toArray, ascii).endsWith("endobj\n")
                                  }
        yield assertTrue(
          restored == samplePdf,
          blocks.length >= 3,
          blocks.forall(_.length <= 128),
          completedObjectBlocks.forall(identity),
        )
      },
      test("rejects bytes that were falsely advertised as a PDF") {
        val chunker = PdfAwareChunker(config())

        for exit <- ZStream.fromChunk(bytes("plain text")).via(chunker.pipeline).runDrain.exit
        yield assertTrue(
          exit match
            case Exit.Failure(cause) =>
              cause.failureOption.exists {
                case ChunkerCore.Err.FormatViolation(format, message) =>
                  format == "pdf" && message.contains("%PDF-")
                case _                                                => false
              }
            case Exit.Success(_)     => false
        )
      },
      test("falls back to bounded fixed cuts for indirect stream lengths") {
        val indirectLengthPdf = bytes(
          "%PDF-1.7\n" +
            "1 0 obj\n<</Length 2 0 R>>\nstream\nabc\nendstream\nendobj\n" +
            "2 0 obj\n3\nendobj\n%%EOF\n"
        )

        for
          blocks  <- ZStream
                       .fromChunk(indirectLengthPdf)
                       .rechunk(5)
                       .via(PdfAwareChunker(config()).pipeline)
                       .runCollect
          restored = blocks.flatMap(block => Block.bytes(block))
        yield assertTrue(
          restored == indirectLengthPdf,
          blocks.forall(_.length <= 128),
        )
      },
      test("can fail closed on an unsupported structural form") {
        val indirectLengthPdf = bytes(
          "%PDF-1.7\n1 0 obj\n<</Length 2 0 R>>\nstream\nabc\nendstream\nendobj\n2 0 obj\n3\nendobj\n"
        )
        val strict            = config(PdfAwareChunker.UnsupportedStructurePolicy.Reject)

        for exit <- ZStream.fromChunk(indirectLengthPdf).rechunk(3).via(PdfAwareChunker(strict).pipeline).runDrain.exit
        yield assertTrue(
          exit match
            case Exit.Failure(cause) =>
              cause.failureOption.exists {
                case ChunkerCore.Err.FormatViolation("pdf", message) => message.contains("indirect /Length")
                case _                                               => false
              }
            case Exit.Success(_)     => false
        )
      },
      test("ingests through the real CAS and restores the caller's chunker context") {
        val pdfConfig = config()

        for
          graviton <- Graviton.inMemory(chunkSize = 32)
          before   <- Chunker.current.get
          result   <- PdfIngest.put(
                        graviton.blobStore,
                        PdfMime.mimeType.copy(parameters = Map("profile" -> "archive")),
                        ZStream.fromChunk(samplePdf).rechunk(11),
                        config = pdfConfig,
                      )
          after    <- Chunker.current.get
          restored <- BoundedByteStream.collectInMemory(graviton.blobStore.get(result.key))
        yield assertTrue(
          restored == samplePdf,
          before eq after,
          result.stats.blockCount >= 3,
          result.attributes.mime.exists(_.value == "application/pdf; profile=archive"),
        )
      },
      test("accepts existing PDF metadata only when its canonical parameters match") {
        val existing = Mime.applyUnsafe("APPLICATION/PDF; version=1; PROFILE=archive")
        val plan     = BlobWritePlan(attributes = BinaryAttributes.empty.advertiseMime(existing))
        val incoming = PdfMime.mimeType.copy(parameters = Map("profile" -> "archive", "version" -> "1"))

        for
          graviton <- Graviton.inMemory()
          result   <- PdfIngest.put(
                        graviton.blobStore,
                        incoming,
                        ZStream.fromChunk(samplePdf).rechunk(13),
                        plan = plan,
                        config = config(),
                      )
        yield assertTrue(
          result.attributes.mime.exists(_.value == "application/pdf; profile=archive; version=1")
        )
      },
      test("rejects conflicting existing PDF parameters before pulling the source") {
        val conflicts = List(
          Mime.applyUnsafe("application/pdf; profile=archive") ->
            PdfMime.mimeType.copy(parameters = Map("profile" -> "screen")),
          Mime.applyUnsafe("application/pdf; profile=archive") ->
            PdfMime.mimeType.copy(parameters = Map("profile" -> "archive", "version" -> "2")),
        )

        for
          pulls    <- Ref.make(0)
          graviton <- Graviton.inMemory()
          exits    <- ZIO.foreach(conflicts) { case (existing, incoming) =>
                        PdfIngest
                          .put(
                            graviton.blobStore,
                            incoming,
                            ZStream.fromZIO(pulls.updateAndGet(_ + 1).as('%'.toByte)),
                            plan = BlobWritePlan(attributes = BinaryAttributes.empty.confirmMime(existing)),
                          )
                          .exit
                      }
          observed <- pulls.get
        yield assertTrue(
          exits.zip(conflicts).forall { case (exit, (existing, _)) =>
            exit.causeOption.flatMap(_.failureOption).contains(PdfIngest.ConflictingMimeMetadata(existing))
          },
          observed == 0,
        )
      },
      test("rejects the wrong typed media value before pulling the source") {
        for
          pulls    <- Ref.make(0)
          graviton <- Graviton.inMemory()
          source    = ZStream.fromZIO(pulls.updateAndGet(_ + 1).as('%'.toByte))
          exit     <- PdfIngest
                        .put(
                          graviton.blobStore,
                          MediaTypes.application.`octet-stream`,
                          source,
                        )
                        .exit
          observed <- pulls.get
        yield assertTrue(
          exit.isFailure,
          observed == 0,
        )
      },
      test("rejects wildcard media ranges before pulling the source") {
        val advertised = List(MediaType("*", "*"), MediaType("application", "*"))

        for
          pulls    <- Ref.make(0)
          graviton <- Graviton.inMemory()
          exits    <- ZIO.foreach(advertised) { mediaType =>
                        PdfIngest
                          .put(
                            graviton.blobStore,
                            mediaType,
                            ZStream.fromZIO(pulls.updateAndGet(_ + 1).as('%'.toByte)),
                          )
                          .exit
                      }
          observed <- pulls.get
        yield assertTrue(
          !PdfIngest.accepts(advertised.head),
          !PdfIngest.accepts(advertised.last),
          exits.forall(_.causeOption.flatMap(_.failureOption).exists(_.isInstanceOf[PdfIngest.UnsupportedMediaType])),
          observed == 0,
        )
      },
      test("publishes a concrete per-upload memory ceiling") {
        val default = PdfAwareChunker.Config.default
        assertTrue(
          default.maximumOwnedBytes ==
            2L * default.maxBytes.value.toLong + default.maxCarryBytes.value.toLong + 5L,
          default.maximumOwnedBytes == 9L * 1024L * 1024L + 5L,
        )
      },
      test("releases the upstream resource when a consumer stops after one block") {
        val repeatedObjects =
          samplePdf ++ Chunk.fromIterable(List.fill(128)(bytes("4 0 obj\n<</Type /Example>>\nendobj\n"))).flatten

        for
          released <- Ref.make(false)
          source    = ZStream
                        .acquireReleaseWith(ZIO.unit)(_ => released.set(true))
                        .flatMap(_ => ZStream.fromChunk(repeatedObjects).rechunk(9))
          _        <- source.via(PdfAwareChunker(config()).pipeline).take(1).runDrain
          observed <- released.get
        yield assertTrue(observed)
      },
    )

end PdfAwareChunkerSpec
