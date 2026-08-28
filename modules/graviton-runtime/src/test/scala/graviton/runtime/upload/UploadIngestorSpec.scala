package graviton.runtime.upload

import graviton.core.types.{FileSize, Identifier, UploadChunkSize}
import graviton.runtime.Graviton
import graviton.streams.Chunker
import UploadProbe.*
import zio.*
import zio.blocks.mediatype.{MediaType, MediaTypes}
import zio.stream.ZStream
import zio.test.*

import java.nio.charset.StandardCharsets

object UploadIngestorSpec extends ZIOSpecDefault:
  private val detectedType = MediaType("application", "x-graviton-test")
  private val detectedKey  = UploadMediaTypeKey.from(detectedType).toOption.get
  private val signature    = Chunk.fromArray("GT".getBytes(StandardCharsets.US_ASCII))

  private def detector(
    onAcquire: UIO[Unit] = ZIO.unit,
    onRelease: UIO[Unit] = ZIO.unit,
  ): UploadMediaTypeDetector =
    UploadMediaTypeDetector.make(
      Identifier.applyUnsafe("graviton-test-signature"),
      UploadProbeSize.applyUnsafe(signature.length),
      Set(detectedKey),
    ) { probe =>
      ZIO.acquireRelease(onAcquire)(_ => onRelease).as(Option.when(probe.bytes.startsWith(signature))(detectedType))
    }

  private def provider(
    acquired: Ref[Int],
    released: Ref[Int],
    initialized: Option[Promise[Nothing, Unit]] = None,
  ): ChunkerProvider =
    ChunkerProvider.make(ChunkerProviderId.applyUnsafe("graviton-test")) { _ =>
      ZIO.acquireRelease(
        acquired.update(_ + 1) *> ZIO.foreachDiscard(initialized)(_.succeed(()).ignore) *>
          ZIO.succeed(Chunker.fixed(UploadChunkSize.applyUnsafe(2)))
      )(_ => released.update(_ + 1))
    }

  private def fallback: ChunkerProvider =
    ChunkerProvider.fixed(
      ChunkerProviderId.applyUnsafe("graviton-fallback"),
      Chunker.fixed(UploadChunkSize.applyUnsafe(8)),
    )

  private def ingestor(
    graviton: Graviton,
    detectorValue: UploadMediaTypeDetector,
    exact: ChunkerProvider,
  ): UploadIngestor =
    UploadIngestor.make(
      graviton.blobStore,
      Chunk.single(detectorValue),
      Map(
        ChunkerProvider.Key.Default                -> fallback,
        ChunkerProvider.Key.MediaType(detectedKey) -> exact,
      ),
    )

  override def spec: Spec[TestEnvironment, Any] =
    suite("UploadIngestor")(
      test("selects and scopes a fresh keyed provider for every detected upload") {
        val payload = Chunk.fromArray("GT-content".getBytes(StandardCharsets.US_ASCII))
        for
          graviton <- Graviton.inMemory()
          acquired <- Ref.make(0)
          released <- Ref.make(0)
          service   = ingestor(graviton, detector(), provider(acquired, released))
          first    <- service.put(UploadIntent(MediaTypes.application.`octet-stream`, None), ZStream.fromChunk(payload))
          second   <- service.put(UploadIntent(MediaTypes.application.`octet-stream`, None), ZStream.fromChunk(payload))
          opens    <- acquired.get
          closes   <- released.get
        yield assertTrue(
          opens == 2,
          closes == 2,
          first.providerId == ChunkerProviderId.applyUnsafe("graviton-test"),
          first.detectedMediaType.contains(detectedType),
          first.effectiveMediaType == detectedType,
          first.stored.stats.blockCount == 5,
          second.stored.key == first.stored.key,
        )
      },
      test("fails a MIME mismatch after only the bounded probe and releases detector state") {
        val payload = Chunk.fromArray("GT-secret-tail".getBytes(StandardCharsets.US_ASCII))
        for
          graviton        <- Graviton.inMemory()
          providerOpens   <- Ref.make(0)
          providerCloses  <- Ref.make(0)
          detectorOpens   <- Ref.make(0)
          detectorCloses  <- Ref.make(0)
          pulls           <- Ref.make(0)
          sourceReleased  <- Ref.make(false)
          source           = ZStream
                               .acquireReleaseWith(ZIO.unit)(_ => sourceReleased.set(true))
                               .flatMap(_ => ZStream.fromChunk(payload).rechunk(1).tap(_ => pulls.update(_ + 1)))
          service          = ingestor(
                               graviton,
                               detector(detectorOpens.update(_ + 1), detectorCloses.update(_ + 1)),
                               provider(providerOpens, providerCloses),
                             )
          exit            <- service.put(UploadIntent(MediaTypes.text.plain, None), source).exit
          observedPulls   <- pulls.get
          detectorStarted <- detectorOpens.get
          detectorEnded   <- detectorCloses.get
          providerStarted <- providerOpens.get
          providerEnded   <- providerCloses.get
          releasedSource  <- sourceReleased.get
          stored          <- graviton.blobStore.list
        yield assertTrue(
          exit.causeOption.flatMap(_.failureOption).exists(_.isInstanceOf[UploadIngestor.Error.MediaTypeMismatch]),
          observedPulls == signature.length,
          detectorStarted == 1,
          detectorEnded == 1,
          providerStarted == 0,
          providerEnded == 0,
          releasedSource,
          stored.isEmpty,
        )
      },
      test("declared-size overflow aborts storage and releases the selected provider") {
        val payload = Chunk.fromArray("GT12".getBytes(StandardCharsets.US_ASCII))
        for
          graviton <- Graviton.inMemory()
          acquired <- Ref.make(0)
          released <- Ref.make(0)
          service   = ingestor(graviton, detector(), provider(acquired, released))
          exit     <- service
                        .put(
                          UploadIntent(MediaTypes.application.`octet-stream`, Some(FileSize.applyUnsafe(3L))),
                          ZStream.fromChunk(payload).rechunk(1),
                        )
                        .exit
          opens    <- acquired.get
          closes   <- released.get
          stored   <- graviton.blobStore.list
        yield assertTrue(
          exit.causeOption.flatMap(_.failureOption).exists(_.isInstanceOf[UploadIngestor.Error.Validation]),
          opens == 1,
          closes == 1,
          stored.isEmpty,
        )
      },
      test("declared-size overflow short-circuits before a larger MIME probe completes") {
        val payload = Chunk.fromArray("GT12".getBytes(StandardCharsets.US_ASCII))
        for
          graviton       <- Graviton.inMemory()
          detectorOpens  <- Ref.make(0)
          providerOpens  <- Ref.make(0)
          providerCloses <- Ref.make(0)
          pulls          <- Ref.make(0)
          service         = ingestor(
                              graviton,
                              detector(detectorOpens.update(_ + 1)),
                              provider(providerOpens, providerCloses),
                            )
          exit           <- service
                              .put(
                                UploadIntent(MediaTypes.application.`octet-stream`, Some(FileSize.applyUnsafe(1L))),
                                ZStream.fromChunk(payload).rechunk(1).tap(_ => pulls.update(_ + 1)),
                              )
                              .exit
          observedPulls  <- pulls.get
          sniffed        <- detectorOpens.get
          opened         <- providerOpens.get
          closed         <- providerCloses.get
          stored         <- graviton.blobStore.list
        yield assertTrue(
          exit.causeOption.flatMap(_.failureOption).exists(_.isInstanceOf[UploadIngestor.Error.Validation]),
          observedPulls == 2,
          sniffed == 0,
          opened == 0,
          closed == 0,
          stored.isEmpty,
        )
      },
      test("interruption releases provider resources and never publishes a manifest") {
        for
          graviton    <- Graviton.inMemory()
          acquired    <- Ref.make(0)
          released    <- Ref.make(0)
          initialized <- Promise.make[Nothing, Unit]
          service      = ingestor(graviton, detector(), provider(acquired, released, Some(initialized)))
          source       = ZStream.fromChunk(signature) ++ ZStream.never
          fiber       <- service.put(UploadIntent(MediaTypes.application.`octet-stream`, None), source).fork
          _           <- initialized.await
          _           <- fiber.interrupt
          opens       <- acquired.get
          closes      <- released.get
          stored      <- graviton.blobStore.list
        yield assertTrue(opens == 1, closes == 1, stored.isEmpty)
      } @@ TestAspect.timeout(5.seconds),
      test("unknown content falls back without pretending the advertised type was detected") {
        val payload = Chunk.fromArray("plain content".getBytes(StandardCharsets.US_ASCII))
        for
          graviton <- Graviton.inMemory()
          acquired <- Ref.make(0)
          released <- Ref.make(0)
          service   = ingestor(graviton, detector(), provider(acquired, released))
          result   <- service.put(UploadIntent(MediaTypes.text.plain, None), ZStream.fromChunk(payload))
          opens    <- acquired.get
        yield assertTrue(
          result.providerId == ChunkerProviderId.applyUnsafe("graviton-fallback"),
          result.detectedMediaType.isEmpty,
          result.effectiveMediaType == MediaTypes.text.plain,
          opens == 0,
        )
      },
      test("a concrete advertised type selects its exact provider when no detector claims the bytes") {
        val payload = Chunk.fromArray("plain content".getBytes(StandardCharsets.US_ASCII))
        val textKey = UploadMediaTypeKey.from(MediaTypes.text.plain).toOption.get
        for
          graviton <- Graviton.inMemory()
          acquired <- Ref.make(0)
          released <- Ref.make(0)
          exact     = provider(acquired, released)
          service   = UploadIngestor.make(
                        graviton.blobStore,
                        Chunk.single(detector()),
                        Map(
                          ChunkerProvider.Key.Default            -> fallback,
                          ChunkerProvider.Key.MediaType(textKey) -> exact,
                        ),
                      )
          result   <- service.put(UploadIntent(MediaTypes.text.plain, None), ZStream.fromChunk(payload))
          opens    <- acquired.get
          closes   <- released.get
        yield assertTrue(
          result.providerId == ChunkerProviderId.applyUnsafe("graviton-test"),
          result.detectedMediaType.isEmpty,
          result.effectiveMediaType == MediaTypes.text.plain,
          opens == 1,
          closes == 1,
        )
      },
    )
