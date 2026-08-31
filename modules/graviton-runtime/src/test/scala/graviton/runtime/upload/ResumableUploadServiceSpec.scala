package graviton.runtime.upload

import graviton.core.keys.{BinaryKey, KeyBits}
import graviton.core.locator.BlobLocator
import graviton.core.types.FileSize
import graviton.runtime.stores.{FsMutableObjectStore, MutableObjectStore, StoreError, StoreOperation}
import zio.*
import zio.blocks.mediatype.MediaTypes
import zio.stream.{ZSink, ZStream}
import zio.test.*

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

object ResumableUploadServiceSpec extends ZIOSpecDefault:
  private val tenant     = TenantId.applyUnsafe("9f2f172c-8e6b-4aef-8be8-4c750420d971")
  private val key        = UploadSessionKey(
    tenant,
    UploadSessionId.applyUnsafe("ab573594-abaa-44fa-867a-8c733bf87f6c"),
  )
  private val firstPart  = UploadPartId.applyUnsafe("11111111-1111-4111-8111-111111111111")
  private val secondPart = UploadPartId.applyUnsafe("22222222-2222-4222-8222-222222222222")
  private val blobKey    = KeyBits
    .fromString(s"sha-256:${"00" * 32}:6")
    .flatMap(BinaryKey.blob)
    .fold(message => throw new IllegalStateException(message), identity)
  private val target     = UploadStagingTarget.from("memory", "tests").toOption.get

  override def spec: Spec[TestEnvironment & Scope, Any] = suite("ResumableUploadService")(
    test("resumes across service instances and makes part and commit retries idempotent") {
      for
        repository <- InMemoryResumableUploadRepository.make
        staging    <- TestObjectStore.make
        first       = new ResumableUploadService(repository, staging, target)
        intent      = UploadIntent(MediaTypes.application.`octet-stream`, Some(FileSize.applyUnsafe(6L)))
        _          <- first.create(key, intent)
        appended   <- first.append(
                        key,
                        firstPart,
                        UploadOffset.applyUnsafe(0L),
                        Some(FileSize.applyUnsafe(3L)),
                        ZStream.fromChunk(Chunk(1, 2, 3).map(_.toByte)),
                      )
        pulled     <- Ref.make(false)
        replayed   <- first.append(
                        key,
                        firstPart,
                        UploadOffset.applyUnsafe(0L),
                        Some(FileSize.applyUnsafe(3L)),
                        ZStream.fromZIO(pulled.set(true).as(9.toByte)),
                      )
        restarted   = new ResumableUploadService(repository, staging, target)
        _          <- restarted.append(
                        key,
                        secondPart,
                        UploadOffset.applyUnsafe(3L),
                        Some(FileSize.applyUnsafe(3L)),
                        ZStream.fromChunk(Chunk(4, 5, 6).map(_.toByte)),
                      )
        finalizes  <- Ref.make(0)
        completed  <- restarted.commit(key) { (_, bytes) =>
                        finalizes.update(_ + 1) *>
                          bytes.runCollect.flatMap(value =>
                            ZIO.fail(new IllegalStateException("wrong bytes")).unless(value == Chunk(1, 2, 3, 4, 5, 6).map(_.toByte))
                          ) *>
                          ZIO.succeed(blobKey)
                      }
        again      <- restarted.commit(key)((_, _) => finalizes.update(_ + 1).as(blobKey))
        calls      <- finalizes.get
        bodyPulled <- pulled.get
      yield assertTrue(
        appended.session.offset.value == 3L,
        !appended.replayed,
        replayed.replayed,
        !bodyPulled,
        completed.blob == blobKey,
        !completed.replayed,
        again.replayed,
        calls == 1,
      )
    },
    test("part limits fail incrementally and release the reservation") {
      val config = ResumableUploadConfig(maxPartBytes = FileSize.applyUnsafe(3L))
      for
        repository <- InMemoryResumableUploadRepository.make
        staging    <- TestObjectStore.make
        service     = new ResumableUploadService(repository, staging, target, config)
        _          <- service.create(key, UploadIntent(MediaTypes.application.`octet-stream`, None))
        pulled     <- Ref.make(0)
        source      = ZStream.fromIterable(1 to 5).mapZIO(value => pulled.update(_ + 1).as(value.toByte))
        failed     <- service
                        .append(key, firstPart, UploadOffset.applyUnsafe(0L), None, source.rechunk(1))
                        .exit
        observed   <- pulled.get
        session    <- service.status(key)
        retry      <- service.append(
                        key,
                        firstPart,
                        UploadOffset.applyUnsafe(0L),
                        Some(FileSize.applyUnsafe(1L)),
                        ZStream.succeed(1.toByte),
                      )
      yield assertTrue(
        failed.isFailure,
        observed == 4,
        session.offset.value == 0L,
        retry.session.offset.value == 1L,
      )
    },
    test("tenant-scoped admission rejects before part body demand and releases the ledger reservation") {
      val rejected = StoreError.DistributedAdmissionUnavailable(StoreOperation.PutBlob, "coordinator offline")
      for
        repository <- InMemoryResumableUploadRepository.make
        staging    <- TestObjectStore.make
        admitted   <- Ref.make(Option.empty[UploadSessionKey])
        pulled     <- Ref.make(false)
        admission   = new ResumablePartAdmission:
                        override def reserveScoped(observed: UploadSessionKey): ZIO[Scope, StoreError, Unit] =
                          admitted.set(Some(observed)) *> ZIO.fail(rejected)
        service     = new ResumableUploadService(
                        repository,
                        staging,
                        target,
                        ResumableUploadConfig.Default,
                        graviton.runtime.metrics.MetricsRegistry.noop,
                        admission,
                      )
        _          <- service.create(key, UploadIntent(MediaTypes.application.`octet-stream`, None))
        failed     <- service
                        .append(
                          key,
                          firstPart,
                          UploadOffset.applyUnsafe(0L),
                          Some(FileSize.applyUnsafe(1L)),
                          ZStream.fromZIO(pulled.set(true).as(1.toByte)),
                        )
                        .exit
        seen       <- admitted.get
        demanded   <- pulled.get
        retry      <- new ResumableUploadService(repository, staging, target).append(
                        key,
                        firstPart,
                        UploadOffset.applyUnsafe(0L),
                        Some(FileSize.applyUnsafe(1L)),
                        ZStream.succeed(1.toByte),
                      )
      yield assertTrue(
        failed.causeOption.flatMap(_.failureOption).contains(ResumableUploadService.Error.Admission(rejected)),
        seen.contains(key),
        !demanded,
        retry.session.offset.value == 1L,
      )
    },
    test("preserves a typed source rejection and cleans the staged reservation") {
      val rejected = UploadSourceError.Rejected("client cancelled the part")
      for
        repository <- InMemoryResumableUploadRepository.make
        staging    <- TestObjectStore.make
        service     = new ResumableUploadService(repository, staging, target)
        _          <- service.create(key, UploadIntent(MediaTypes.application.`octet-stream`, None))
        failed     <- service
                        .appendSource(
                          key,
                          firstPart,
                          UploadOffset.applyUnsafe(0L),
                          None,
                          UploadSource.typed(ZStream.fail(rejected)),
                        )
                        .exit
        objects    <- staging.size
        session    <- service.status(key)
      yield assertTrue(
        failed.causeOption.flatMap(_.failureOption).contains(ResumableUploadService.Error.Source(rejected)),
        objects == 0,
        session.offset.value == 0L,
      )
    },
    test("interruption releases the source, staging object, and part lease") {
      for
        repository <- InMemoryResumableUploadRepository.make
        staging    <- TestObjectStore.make
        service     = new ResumableUploadService(repository, staging, target)
        _          <- service.create(key, UploadIntent(MediaTypes.application.`octet-stream`, None))
        started    <- Promise.make[Nothing, Unit]
        released   <- Promise.make[Nothing, Unit]
        source      = (ZStream.fromZIO(started.succeed(()).as(1.toByte)) ++ ZStream.never)
                        .ensuring(released.succeed(()).unit)
        fiber      <- service.append(key, firstPart, UploadOffset.applyUnsafe(0L), None, source).fork
        _          <- started.await
        exit       <- fiber.interrupt
        _          <- released.await
        objects    <- staging.size
        retry      <- service.append(
                        key,
                        firstPart,
                        UploadOffset.applyUnsafe(0L),
                        Some(FileSize.applyUnsafe(1L)),
                        ZStream.succeed(1.toByte),
                      )
      yield assertTrue(exit.isFailure, objects == 0, retry.session.offset.value == 1L)
    },
    test("TestClock-driven expiry removes staged objects and ledger state") {
      val config = ResumableUploadConfig(sessionTtl = 1.minute)
      for
        repository <- InMemoryResumableUploadRepository.make
        staging    <- TestObjectStore.make
        service     = new ResumableUploadService(repository, staging, target, config)
        _          <- service.create(key, UploadIntent(MediaTypes.application.`octet-stream`, None))
        _          <- service.append(
                        key,
                        firstPart,
                        UploadOffset.applyUnsafe(0L),
                        Some(FileSize.applyUnsafe(1L)),
                        ZStream.succeed(1.toByte),
                      )
        _          <- TestClock.adjust(2.minutes)
        cleaned    <- service.cleanupExpired
        objects    <- staging.size
        status     <- service.status(key).exit
      yield assertTrue(cleaned == 1L, objects == 0, status.isFailure)
    },
    test("maintenance finishes committed staging cleanup after a process crash") {
      val commitLease = UploadLeaseId.applyUnsafe("33333333-3333-4333-8333-333333333333")
      for
        repository <- InMemoryResumableUploadRepository.make
        staging    <- TestObjectStore.make
        service     = new ResumableUploadService(repository, staging, target)
        _          <- service.create(key, UploadIntent(MediaTypes.application.`octet-stream`, Some(FileSize.applyUnsafe(1L))))
        appended   <- service.append(
                        key,
                        firstPart,
                        UploadOffset.applyUnsafe(0L),
                        Some(FileSize.applyUnsafe(1L)),
                        ZStream.succeed(1.toByte),
                      )
        now        <- Clock.instant
        reserved   <- repository.reserveCommit(key, commitLease, now, now.plusSeconds(60))
        _          <- reserved match
                        case UploadCommitReservationResult.Reserved(_, lease) =>
                          repository.completeCommit(key, lease, blobKey, now)
                        case _                                                =>
                          ZIO.fail(new IllegalStateException("expected a fresh commit lease"))
        before     <- staging.head(appended.part.locator)
        restarted   = new ResumableUploadService(repository, staging, target)
        cleaned    <- restarted.cleanupExpired
        after      <- staging.head(appended.part.locator)
        parts      <- repository.parts(key).runCollect
        status     <- restarted.status(key)
      yield assertTrue(
        before.contains(1L),
        cleaned == 0L,
        after.isEmpty,
        parts.isEmpty,
        status.phase == ResumableUploadPhase.Committed,
        status.committedBlob.contains(blobKey),
      )
    },
    test("filesystem repository and staging resume after both services are reconstructed") {
      ZIO.scoped {
        for
          root        <- temporaryDirectory
          fileTarget  <- ZIO.fromEither(UploadStagingTarget.from("file", "graviton-staging"))
          firstRepo    = new FsResumableUploadRepository(root)
          firstStore   = new FsMutableObjectStore(root)
          first        = new ResumableUploadService(firstRepo, firstStore, fileTarget)
          _           <- first.create(
                           key,
                           UploadIntent(MediaTypes.application.`octet-stream`, Some(FileSize.applyUnsafe(6L))),
                         )
          firstResult <- first.append(
                           key,
                           firstPart,
                           UploadOffset.applyUnsafe(0L),
                           Some(FileSize.applyUnsafe(3L)),
                           ZStream.fromChunk(Chunk(1, 2, 3).map(_.toByte)),
                         )
          secondRepo   = new FsResumableUploadRepository(root)
          secondStore  = new FsMutableObjectStore(root)
          restarted    = new ResumableUploadService(secondRepo, secondStore, fileTarget)
          status      <- restarted.status(key)
          _           <- restarted.append(
                           key,
                           secondPart,
                           UploadOffset.applyUnsafe(3L),
                           Some(FileSize.applyUnsafe(3L)),
                           ZStream.fromChunk(Chunk(4, 5, 6).map(_.toByte)),
                         )
          completed   <- restarted.commit(key)((_, bytes) => bytes.runDrain.as(blobKey))
          cleaned     <- secondStore
                           .head(firstResult.part.locator)
                           .repeatUntil(_.isEmpty)
                           .timeoutFail(new IllegalStateException("staging cleanup did not finish"))(2.seconds)
        yield assertTrue(
          status.offset.value == 3L,
          completed.blob == blobKey,
          cleaned.isEmpty,
        )
      }
    },
  )

  private def temporaryDirectory: ZIO[Scope, Throwable, Path] =
    ZIO.acquireRelease(ZIO.attemptBlocking(Files.createTempDirectory("graviton-resumable-")))(deleteTree)

  private def deleteTree(path: Path): UIO[Unit] =
    ZIO.attemptBlocking {
      if Files.exists(path) then
        val paths = Files.walk(path)
        try paths.iterator().asScala.toVector.sortBy(_.getNameCount).reverse.foreach(Files.deleteIfExists)
        finally paths.close()
    }.orDie

  private final class TestObjectStore(ref: Ref[Map[BlobLocator, Chunk[Byte]]]) extends MutableObjectStore:
    override def put(locator: BlobLocator): ZSink[Any, StoreError, Byte, Nothing, Unit] =
      ZSink.collectAll[Byte].mapZIO(bytes => ref.update(_.updated(locator, bytes)))

    override def delete(locator: BlobLocator): UIO[Unit] =
      ref.update(_ - locator)

    override def copy(src: BlobLocator, dest: BlobLocator): IO[StoreError, Unit] =
      ref.get
        .flatMap(values => ZIO.fromOption(values.get(src)).orElseFail(StoreError.ObjectNotFound(StoreOperation.CopyObject, src)))
        .flatMap { bytes =>
          ref.update(_.updated(dest, bytes))
        }

    override def head(locator: BlobLocator): UIO[Option[Long]] =
      ref.get.map(_.get(locator).map(_.length.toLong))

    override def list(prefix: String): ZStream[Any, Nothing, BlobLocator] =
      ZStream.fromZIO(ref.get).flatMap(values => ZStream.fromIterable(values.keys.filter(_.path.value.startsWith(prefix))))

    override def get(locator: BlobLocator): ZStream[Any, StoreError, Byte] =
      ZStream
        .fromZIO(
          ref.get
            .flatMap(values => ZIO.fromOption(values.get(locator)).orElseFail(StoreError.ObjectNotFound(StoreOperation.GetObject, locator)))
        )
        .flattenChunks

    def size: UIO[Int] = ref.get.map(_.size)

  private object TestObjectStore:
    def make: UIO[TestObjectStore] = Ref.make(Map.empty[BlobLocator, Chunk[Byte]]).map(new TestObjectStore(_))
