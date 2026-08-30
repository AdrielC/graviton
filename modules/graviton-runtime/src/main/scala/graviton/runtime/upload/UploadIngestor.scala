package graviton.runtime.upload

import graviton.core.RefinedTypeExt
import graviton.core.types.{FileSize, Identifier, IdentifierConstraint}
import graviton.runtime.model.{BlobWritePlan, BlobWriteResult}
import graviton.runtime.stores.{BlobStore, StoreError}
import graviton.shared.MediaTypeText
import graviton.streams.Chunker
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.all.*
import io.github.iltotore.iron.constraint.numeric
import zio.*
import zio.blocks.mediatype.{MediaType, MediaTypes}
import zio.stream.ZStream

/** A bounded prefix inspected before an upload chooses its chunker. */
type UploadProbe = Chunk[Byte] :| UploadProbe.Constraint

object UploadProbe:
  type Constraint = MaxLength[4096]
  inline val MaxBytes = 4096

  val empty: UploadProbe =
    fromChunk(Chunk.empty).fold(message => throw new IllegalStateException(message), identity)

  def fromChunk(bytes: Chunk[Byte]): Either[String, UploadProbe] =
    bytes.refineEither[Constraint]

  extension (probe: UploadProbe) def bytes: Chunk[Byte] = probe

import UploadProbe.*

type UploadProbeSize = UploadProbeSize.T
object UploadProbeSize extends RefinedTypeExt[Int, numeric.GreaterEqual[1] & numeric.LessEqual[4096]]

type ChunkerProviderId = ChunkerProviderId.T
object ChunkerProviderId extends RefinedTypeExt[String, IdentifierConstraint]

/** Parameter-insensitive, normalized key for upload classification and provider lookup. */
final case class UploadMediaTypeKey private (value: MediaType):
  def render: String = value.fullType

object UploadMediaTypeKey:
  def from(value: MediaType): Either[String, UploadMediaTypeKey] =
    if value == null then Left("media type must not be null")
    else
      val main = value.mainType.toLowerCase(java.util.Locale.ROOT)
      val sub  = value.subType.toLowerCase(java.util.Locale.ROOT)
      Either.cond(
        main.nonEmpty && sub.nonEmpty && main != "*" && sub != "*",
        UploadMediaTypeKey(MediaType(main, sub)),
        s"media type '${value.fullType}' must identify one concrete type",
      )

/** A detector receives only the compile-time bounded upload prefix. */
trait UploadMediaTypeDetector:
  def id: Identifier
  def probeSize: UploadProbeSize
  def supported: Set[UploadMediaTypeKey]
  def mismatchMessage(advertised: MediaType): String                                                =
    s"advertised ${advertised.fullType} does not match the upload prefix"
  def detect(probe: UploadProbe): ZIO[Scope, Throwable, Option[MediaType]]
  def detectTyped(probe: UploadProbe): ZIO[Scope, UploadMediaTypeDetector.Error, Option[MediaType]] =
    detect(probe).mapError(UploadMediaTypeDetector.Error.Legacy(id, _))

object UploadMediaTypeDetector:
  sealed abstract class Error(message: String, cause: Throwable | Null = null) extends Exception(message, cause)
  object Error:
    final case class DetectionFailed(detector: Identifier, reason: String, underlying: Throwable | Null = null)
        extends Error(s"media detector '${detector.value}' failed: $reason", underlying)
    final case class Legacy(detector: Identifier, underlying: Throwable)
        extends Error(s"legacy media detector '${detector.value}' failed", underlying)

  def make(
    detectorId: Identifier,
    requiredBytes: UploadProbeSize,
    supportedTypes: Set[UploadMediaTypeKey],
    mismatch: MediaType => String = advertised => s"advertised ${advertised.fullType} does not match the upload prefix",
  )(
    run: UploadProbe => ZIO[Scope, Throwable, Option[MediaType]]
  ): UploadMediaTypeDetector =
    new UploadMediaTypeDetector:
      override val id: Identifier                                                       = detectorId
      override val probeSize: UploadProbeSize                                           = requiredBytes
      override val supported: Set[UploadMediaTypeKey]                                   = supportedTypes
      override def mismatchMessage(advertised: MediaType): String                       = mismatch(advertised)
      override def detect(probe: UploadProbe): ZIO[Scope, Throwable, Option[MediaType]] = run(probe)

  def makeTyped(
    detectorId: Identifier,
    requiredBytes: UploadProbeSize,
    supportedTypes: Set[UploadMediaTypeKey],
    mismatch: MediaType => String = advertised => s"advertised ${advertised.fullType} does not match the upload prefix",
  )(
    run: UploadProbe => ZIO[Scope, Error, Option[MediaType]]
  ): UploadMediaTypeDetector =
    new UploadMediaTypeDetector:
      override val id: Identifier                                                        = detectorId
      override val probeSize: UploadProbeSize                                            = requiredBytes
      override val supported: Set[UploadMediaTypeKey]                                    = supportedTypes
      override def mismatchMessage(advertised: MediaType): String                        = mismatch(advertised)
      override def detect(probe: UploadProbe): ZIO[Scope, Throwable, Option[MediaType]]  = run(probe)
      override def detectTyped(probe: UploadProbe): ZIO[Scope, Error, Option[MediaType]] = run(probe)

/**
 * Acquires one fresh chunker for one upload.
 *
 * Implementations may allocate parser state, temporary files, native handles,
 * or other resources with `acquireRelease`. Their finalizers remain alive until
 * the upload succeeds, fails, or is interrupted.
 */
trait ChunkerProvider:
  def id: ChunkerProviderId
  def acquire(context: ChunkerProvider.Context): ZIO[Scope, Throwable, Chunker]
  def acquireTyped(context: ChunkerProvider.Context): ZIO[Scope, ChunkerProvider.Error, Chunker] =
    acquire(context).mapError(ChunkerProvider.Error.Legacy(id, _))

object ChunkerProvider:
  sealed abstract class Error(message: String, cause: Throwable | Null = null) extends Exception(message, cause)
  object Error:
    final case class InitializationFailed(provider: ChunkerProviderId, reason: String, underlying: Throwable | Null = null)
        extends Error(s"chunker provider '${provider.value}' failed: $reason", underlying)
    final case class Legacy(provider: ChunkerProviderId, underlying: Throwable)
        extends Error(s"legacy chunker provider '${provider.value}' failed", underlying)

  enum Key derives CanEqual:
    case Default
    case MediaType(value: UploadMediaTypeKey)

  final case class Context(
    advertisedMediaType: MediaType,
    detectedMediaType: Option[MediaType],
    effectiveMediaType: MediaType,
    expectedSize: Option[FileSize],
    probe: UploadProbe,
  )

  def make(providerId: ChunkerProviderId)(open: Context => ZIO[Scope, Throwable, Chunker]): ChunkerProvider =
    new ChunkerProvider:
      override val id: ChunkerProviderId                                     = providerId
      override def acquire(context: Context): ZIO[Scope, Throwable, Chunker] = open(context)

  def makeTyped(providerId: ChunkerProviderId)(open: Context => ZIO[Scope, Error, Chunker]): ChunkerProvider =
    new ChunkerProvider:
      override val id: ChunkerProviderId                                      = providerId
      override def acquire(context: Context): ZIO[Scope, Throwable, Chunker]  = open(context)
      override def acquireTyped(context: Context): ZIO[Scope, Error, Chunker] = open(context)

  def fixed(providerId: ChunkerProviderId, chunker: => Chunker): ChunkerProvider =
    make(providerId)(_ => ZIO.succeed(chunker))

  val current: ChunkerProvider =
    make(ChunkerProviderId.applyUnsafe("graviton-current"))(_ => Chunker.current.get)

  /** Uses ZIO 2.1's keyed service lookup and falls back only to the default provider. */
  final class Registry private (providers: Map[Key, ChunkerProvider]):
    private val environment = ZEnvironment[Map[Key, ChunkerProvider]](providers)

    def acquire(key: Key, context: Context): ZIO[Scope, UploadIngestor.Error, (ChunkerProviderId, Chunker)] =
      for
        exact    <- lookup(key)
        fallback <- if key == Key.Default || exact.isDefined then ZIO.none else lookup(Key.Default)
        provider <- ZIO
                      .fromOption(exact.orElse(fallback))
                      .orElseFail(UploadIngestor.Error.MissingProvider(renderKey(key)))
        chunker  <- provider
                      .acquireTyped(context)
                      .mapError(cause => UploadIngestor.Error.ProviderError(provider.id, cause))
      yield provider.id -> chunker

    private def lookup(key: Key): UIO[Option[ChunkerProvider]] =
      ZIO.serviceAt[ChunkerProvider](key).provideEnvironment(environment)

  object Registry:
    def apply(providers: Map[Key, ChunkerProvider]): Registry = new Registry(providers)

  private def renderKey(key: Key): String =
    key match
      case Key.Default          => "default"
      case Key.MediaType(value) => value.render

/** One-pass upload preparation, validation, provider acquisition, and storage. */
trait UploadIngestor:
  def put(
    intent: UploadIntent,
    bytes: ZStream[Any, Throwable, Byte],
    plan: BlobWritePlan = BlobWritePlan(),
  ): IO[UploadIngestor.Error, UploadIngestor.Result]

  def putSource(
    intent: UploadIntent,
    source: UploadSource,
    plan: BlobWritePlan = BlobWritePlan(),
  ): IO[UploadIngestor.Error, UploadIngestor.Result] =
    put(intent, source.bytes.mapError(identity[Throwable]), plan)

object UploadIngestor:
  final case class Result(
    stored: BlobWriteResult,
    advertisedMediaType: MediaType,
    detectedMediaType: Option[MediaType],
    effectiveMediaType: MediaType,
    providerId: ChunkerProviderId,
  )

  sealed abstract class Error(message: String, cause: Throwable = null) extends Exception(message, cause)

  object Error:
    final case class InvalidInput(detail: String)                   extends Error(detail)
    final case class DetectorFailure(detector: Identifier, underlying: Throwable)
        extends Error(s"media detector '${detector.value}' failed", underlying)
    final case class DetectorError(detector: Identifier, underlying: UploadMediaTypeDetector.Error)
        extends Error(s"media detector '${detector.value}' failed", underlying)
    final case class AmbiguousDetection(mediaTypes: Chunk[MediaType])
        extends Error(s"upload prefix matched multiple media types: ${mediaTypes.map(_.fullType).mkString(", ")}")
    final case class MediaTypeMismatch(advertised: MediaType, detected: Option[MediaType], detail: Option[String] = None)
        extends Error(
          detail.getOrElse(
            s"advertised ${advertised.fullType} does not match detected ${detected.fold("unknown content")(_.fullType)}"
          )
        )
    final case class MissingProvider(key: String)                   extends Error(s"no chunker provider is registered for '$key' or the default key")
    final case class ProviderInitialization(provider: ChunkerProviderId, underlying: Throwable)
        extends Error(s"chunker provider '${provider.value}' could not initialize", underlying)
    final case class ProviderError(provider: ChunkerProviderId, underlying: ChunkerProvider.Error)
        extends Error(s"chunker provider '${provider.value}' could not initialize", underlying)
    final case class Validation(underlying: UploadByteStream.Error) extends Error(underlying.getMessage, underlying)
    final case class Source(underlying: Throwable)                  extends Error("upload source failed", underlying)
    final case class SourceError(underlying: UploadSourceError)     extends Error("upload source failed", underlying)
    final case class Storage(underlying: StoreError)                extends Error("blob storage failed", underlying)

  def put(
    intent: UploadIntent,
    bytes: ZStream[Any, Throwable, Byte],
    plan: BlobWritePlan = BlobWritePlan(),
  ): ZIO[UploadIngestor, Error, Result] =
    ZIO.serviceWithZIO[UploadIngestor](_.put(intent, bytes, plan))

  def putSource(
    intent: UploadIntent,
    source: UploadSource,
    plan: BlobWritePlan = BlobWritePlan(),
  ): ZIO[UploadIngestor, Error, Result] =
    ZIO.serviceWithZIO[UploadIngestor](_.putSource(intent, source, plan))

  def make(
    store: BlobStore,
    detectors: Chunk[UploadMediaTypeDetector],
    providers: Map[ChunkerProvider.Key, ChunkerProvider],
  ): UploadIngestor =
    Live(store, detectors, ChunkerProvider.Registry(providers))

  def default(store: BlobStore): UploadIngestor =
    make(
      store,
      Chunk.empty,
      Map(ChunkerProvider.Key.Default -> ChunkerProvider.current),
    )

  def layer(
    detectors: Chunk[UploadMediaTypeDetector],
    providers: Map[ChunkerProvider.Key, ChunkerProvider],
  ): ZLayer[BlobStore, Nothing, UploadIngestor] =
    ZLayer.fromFunction((store: BlobStore) => make(store, detectors, providers))

  private final case class Live(
    store: BlobStore,
    detectors: Chunk[UploadMediaTypeDetector],
    registry: ChunkerProvider.Registry,
  ) extends UploadIngestor:
    private val probeLimit: Option[Int]                      = detectors.map(_.probeSize.value).maxOption
    private val supportedMediaTypes: Set[UploadMediaTypeKey] = detectors.iterator.flatMap(_.supported).toSet

    override def put(
      intent: UploadIntent,
      bytes: ZStream[Any, Throwable, Byte],
      plan: BlobWritePlan,
    ): IO[Error, Result] =
      putSource(intent, UploadSource.fromThrowable(bytes), plan)

    override def putSource(
      intent: UploadIntent,
      source: UploadSource,
      plan: BlobWritePlan,
    ): IO[Error, Result] =
      ZIO.scoped {
        for
          advertised           <- canonical(intent.contentType)
          validated             = UploadByteStream
                                    .enforceExpectedSizeTyped(source.bytes, intent.expectedSize)
                                    .mapError {
                                      case error: UploadByteStream.Error => Error.Validation(error)
                                      case error: UploadSourceError      => Error.SourceError(error)
                                    }
          probed               <- probe(validated)
          detected             <- detect(probed.prefix)
          effective            <- resolveMediaType(advertised, detected)
          key                  <- providerKey(effective)
          context               = ChunkerProvider.Context(
                                    advertised,
                                    detected,
                                    effective,
                                    intent.expectedSize,
                                    probed.prefix,
                                  )
          selected             <- registry.acquire(key, context)
          (providerId, chunker) = selected
          writePlan            <- enrich(plan, intent.expectedSize, advertised, detected.map(_ => effective))
          stored               <- Chunker.locally(chunker) {
                                    probed.bytes.run(store.put(writePlan).mapError(Error.Storage.apply))
                                  }
        yield Result(stored, advertised, detected, effective, providerId)
      }

    private final case class Probed(prefix: UploadProbe, bytes: ZStream[Any, Error, Byte])

    private def probe(bytes: ZStream[Any, Error, Byte]): ZIO[Scope, Error, Probed] =
      probeLimit match
        case None        => ZIO.succeed(Probed(UploadProbe.empty, bytes))
        case Some(limit) =>
          for
            pull                    <- bytes.toPull
            builder                 <- ZIO.succeed(ChunkBuilder.make[Byte](limit))
            (prefix, leftover, end) <- pullPrefix(pull, limit, builder, 0)
            bounded                 <- ZIO.fromEither(UploadProbe.fromChunk(prefix)).mapError(Error.InvalidInput.apply)
            remainder                = if end then ZStream.empty else ZStream.fromChunk(leftover) ++ ZStream.repeatZIOChunkOption(pull)
          yield Probed(bounded, ZStream.fromChunk(bounded.bytes) ++ remainder)

    private def pullPrefix(
      pull: IO[Option[Error], Chunk[Byte]],
      limit: Int,
      builder: ChunkBuilder[Byte],
      accumulated: Int,
    ): IO[Error, (Chunk[Byte], Chunk[Byte], Boolean)] =
      if accumulated >= limit then ZIO.succeed((builder.result(), Chunk.empty, false))
      else
        pull.foldZIO(
          {
            case Some(error) => ZIO.fail(error)
            case None        => ZIO.succeed((builder.result(), Chunk.empty, true))
          },
          chunk =>
            if chunk.isEmpty then pullPrefix(pull, limit, builder, accumulated)
            else
              val needed = limit - accumulated
              if chunk.length <= needed then
                ZIO.succeed(builder.addAll(chunk)) *> pullPrefix(pull, limit, builder, accumulated + chunk.length)
              else
                ZIO.succeed {
                  builder.addAll(chunk.take(needed))
                  (builder.result(), chunk.drop(needed), false)
                },
        )

    private def detect(probe: UploadProbe): ZIO[Scope, Error, Option[MediaType]] =
      ZIO
        .foreachPar(detectors)(detector =>
          ZIO
            .scoped(detector.detectTyped(probe))
            .mapError(Error.DetectorError(detector.id, _))
        )
        .flatMap { matches =>
          val distinct = matches.flatten.distinct
          if distinct.length <= 1 then ZIO.succeed(distinct.headOption)
          else ZIO.fail(Error.AmbiguousDetection(distinct))
        }

    private def resolveMediaType(advertised: MediaType, detected: Option[MediaType]): IO[Error, MediaType] =
      for
        advertisedKey <- ZIO.fromEither(UploadMediaTypeKey.from(advertised)).mapError(Error.InvalidInput.apply)
        detectedKey   <- ZIO.foreach(detected)(value => ZIO.fromEither(UploadMediaTypeKey.from(value)).mapError(Error.InvalidInput.apply))
        genericKey    <- ZIO
                           .fromEither(UploadMediaTypeKey.from(MediaTypes.application.`octet-stream`))
                           .mapError(Error.InvalidInput.apply)
        result        <- (detected, detectedKey) match
                           case (Some(value), Some(found)) if advertisedKey == genericKey || advertisedKey == found =>
                             ZIO.succeed(if advertisedKey == found then advertised else value)
                           case (Some(_), Some(_))                                                                  =>
                             ZIO.fail(Error.MediaTypeMismatch(advertised, detected))
                           case (None, None) if supportedMediaTypes.contains(advertisedKey)                         =>
                             val detail = detectors.find(_.supported.contains(advertisedKey)).map(_.mismatchMessage(advertised))
                             ZIO.fail(Error.MediaTypeMismatch(advertised, None, detail))
                           case (None, None)                                                                        => ZIO.succeed(advertised)
                           case _                                                                                   =>
                             ZIO.fail(Error.InvalidInput("detected media type could not be normalized"))
      yield result

    private def providerKey(effective: MediaType): IO[Error, ChunkerProvider.Key] =
      ZIO
        .fromEither(UploadMediaTypeKey.from(effective))
        .mapBoth(Error.InvalidInput.apply, ChunkerProvider.Key.MediaType.apply)

    private def enrich(
      plan: BlobWritePlan,
      expectedSize: Option[FileSize],
      advertised: MediaType,
      confirmed: Option[MediaType],
    ): IO[Error, BlobWritePlan] =
      for
        _              <- plan.attributes.size match
                            case Some(existing) if expectedSize.exists(_ != existing) =>
                              ZIO.fail(
                                Error.InvalidInput(s"upload plan declares ${existing.value} bytes but request declares ${expectedSize.get.value}")
                              )
                            case _                                                    => ZIO.unit
        existingMedia  <- ZIO.fromEither(plan.attributes.mediaType).mapError(Error.InvalidInput.apply)
        advertisedText <- ZIO.fromEither(MediaTypeText.renderEither(advertised)).mapError(Error.InvalidInput.apply)
        _              <- existingMedia match
                            case Some(existing) =>
                              ZIO
                                .fromEither(MediaTypeText.renderEither(existing))
                                .mapError(Error.InvalidInput.apply)
                                .flatMap(existingText =>
                                  ZIO
                                    .fail(Error.InvalidInput(s"upload plan declares $existingText but request advertises $advertisedText"))
                                    .unless(existingText == advertisedText)
                                )
                            case None           => ZIO.unit
        withSize        = expectedSize.fold(plan.attributes)(plan.attributes.advertiseSize)
        withAdvertised <- ZIO.fromEither(withSize.advertiseMediaType(advertised)).mapError(Error.InvalidInput.apply)
        attributes     <- confirmed match
                            case None        => ZIO.succeed(withAdvertised)
                            case Some(value) =>
                              ZIO.fromEither(withAdvertised.confirmMediaType(value)).mapError(Error.InvalidInput.apply)
      yield plan.copy(attributes = attributes)

    private def canonical(value: MediaType): IO[Error, MediaType] =
      ZIO
        .fromEither(MediaTypeText.renderEither(value).flatMap(MediaTypeText.parse))
        .mapError(Error.InvalidInput.apply)
