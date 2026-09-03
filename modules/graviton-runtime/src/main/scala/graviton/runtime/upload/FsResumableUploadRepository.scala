package graviton.runtime.upload

import graviton.core.RefinedTypeExt
import graviton.core.keys.{BinaryKey, KeyBits}
import graviton.core.locator.BlobLocator
import graviton.core.types.FileSize
import graviton.runtime.upload.ResumableUploadRepository.Error
import graviton.shared.{ApiJson, ApiJsonCodec, MediaTypeText}
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.all.*
import zio.*
import zio.blocks.schema.Schema as BlocksSchema
import zio.stream.ZStream

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.channels.FileChannel
import java.nio.file.{Files, LinkOption, Path, StandardCopyOption, StandardOpenOption}
import java.time.Instant
import scala.jdk.CollectionConverters.*

/**
 * Crash-safe local session ledger.
 *
 * Every transition rewrites one compile-time bounded ZIO Blocks document and
 * commits it with fsync plus atomic rename. Filesystem deployments are
 * intentionally single-node; PostgreSQL provides cross-node serialization.
 */
final class FsResumableUploadRepository(root: Path) extends ResumableUploadRepository:
  import FsResumableUploadRepository.*

  private val base       = root.resolve("cas/upload-sessions").toAbsolutePath.normalize()
  private val lockShards = Array.fill(SessionLockShards)(new Object())

  override def healthCheck: IO[Error, Unit] =
    fromBlocking("health check") {
      Files.createDirectories(base)
      if !Files.isDirectory(base, LinkOption.NOFOLLOW_LINKS) || !Files.isWritable(base) then
        throw new IllegalStateException(s"resumable ledger root is not writable: $base")
    }

  override def create(
    key: UploadSessionKey,
    intent: UploadIntent,
    createdAt: Instant,
    expiresAt: Instant,
  ): IO[Error, ResumableUploadSession] =
    val session = ResumableUploadSession(
      key,
      intent,
      UploadOffset.applyUnsafe(0L),
      UploadPartNumber.applyUnsafe(0),
      createdAt,
      expiresAt,
      ResumableUploadPhase.Open,
      None,
    )
    withLock(key) {
      val path = pathFor(key)
      if Files.exists(path, LinkOption.NOFOLLOW_LINKS) then Left(Error.AlreadyExists(key))
      else write(path, ResumableUploadLedger.initial(session)).map(_ => session)
    }

  override def get(key: UploadSessionKey, now: Instant): IO[Error, Option[ResumableUploadSession]] =
    withLock(key) {
      readOptional(pathFor(key)).flatMap {
        case None         => Right(None)
        case Some(ledger) => ResumableUploadLedger.current(ledger, now).map(Some(_))
      }
    }

  override def reservePart(
    key: UploadSessionKey,
    partId: UploadPartId,
    expectedOffset: UploadOffset,
    locator: BlobLocator,
    leaseId: UploadLeaseId,
    now: Instant,
    leaseExpiresAt: Instant,
    maxParts: UploadPartNumber,
  ): IO[Error, UploadPartReservationResult] =
    modify(key)(ResumableUploadLedger.reservePart(_, partId, expectedOffset, locator, leaseId, now, leaseExpiresAt, maxParts))

  override def completePart(
    reservation: UploadPartReservation,
    size: FileSize,
    now: Instant,
  ): IO[Error, ResumableUploadSession] =
    modify(reservation.key)(ResumableUploadLedger.completePart(_, reservation, size, now))

  override def abortPart(reservation: UploadPartReservation): UIO[Unit] =
    updateBestEffort(reservation.key)(ResumableUploadLedger.abortPart(_, reservation))

  override def parts(key: UploadSessionKey): ZStream[Any, Error, ResumableUploadPart] =
    ZStream.fromZIO(loadRequired(key)).flatMap(ledger => ZStream.fromIterable(ledger.parts))

  override def reserveCommit(
    key: UploadSessionKey,
    leaseId: UploadLeaseId,
    now: Instant,
    leaseExpiresAt: Instant,
  ): IO[Error, UploadCommitReservationResult] =
    modify(key)(ResumableUploadLedger.reserveCommit(_, leaseId, now, leaseExpiresAt))

  override def completeCommit(
    key: UploadSessionKey,
    leaseId: UploadLeaseId,
    blob: BinaryKey.Blob,
    now: Instant,
  ): IO[Error, ResumableUploadSession] =
    modify(key)(ResumableUploadLedger.completeCommit(_, leaseId, blob, now))

  override def releaseCommit(key: UploadSessionKey, leaseId: UploadLeaseId): UIO[Unit] =
    updateBestEffort(key)(ResumableUploadLedger.releaseCommit(_, leaseId))

  override def cancel(key: UploadSessionKey, now: Instant): IO[Error, ResumableUploadSession] =
    modify(key)(ResumableUploadLedger.cancel(_, now))

  override def expired(before: Instant): ZStream[Any, Error, UploadSessionKey] =
    ledgerFiles
      .mapError(error => Error.Storage("list", error))
      .mapZIO(path => fromBlocking("read")(read(path)).flatMap(ZIO.fromEither))
      .filter(ledger => ResumableUploadLedger.isExpired(ledger.session, before))
      .map(_.session.key)

  override def cleanupPending: ZStream[Any, Error, UploadSessionKey] =
    ledgerFiles
      .mapError(error => Error.Storage("list", error))
      .mapZIO(path => fromBlocking("read")(read(path)).flatMap(ZIO.fromEither))
      .filter(ledger => ledger.session.phase == ResumableUploadPhase.Committed && ledger.parts.nonEmpty)
      .map(_.session.key)

  override def clearParts(key: UploadSessionKey): IO[Error, Unit] =
    modify(key)(ledger => Right(ledger.copy(parts = Vector.empty) -> ()))

  override def delete(key: UploadSessionKey): IO[Error, Unit] =
    withLock(key) {
      try
        val _ = Files.deleteIfExists(pathFor(key))
        Right(())
      catch case error: Throwable => Left(Error.Storage("delete", error))
    }

  private def modify[A](
    key: UploadSessionKey
  )(
    operation: ResumableUploadLedger => Either[Error, (ResumableUploadLedger, A)]
  ): IO[Error, A] =
    withLock(key) {
      for
        current       <- readRequired(pathFor(key), key)
        (next, value) <- operation(current)
        _             <- write(pathFor(key), next)
      yield value
    }

  private def updateBestEffort(
    key: UploadSessionKey
  )(
    operation: ResumableUploadLedger => ResumableUploadLedger
  ): UIO[Unit] =
    modify(key)(ledger => Right(operation(ledger) -> ()))
      .catchAll(error => ZIO.logErrorCause(error.getMessage, Cause.fail(error)))

  private def loadRequired(key: UploadSessionKey): IO[Error, ResumableUploadLedger] =
    withLock(key)(readRequired(pathFor(key), key))

  private def withLock[A](key: UploadSessionKey)(operation: => Either[Error, A]): IO[Error, A] =
    fromBlocking("transition") {
      val lock = lockShards(java.lang.Math.floorMod(key.hashCode, lockShards.length))
      lock.synchronized(operation)
    }.flatMap(ZIO.fromEither)

  private def pathFor(key: UploadSessionKey): Path =
    base.resolve(key.tenantId.value).resolve(s"${key.uploadSessionId.value}.json")

  private def readRequired(path: Path, key: UploadSessionKey): Either[Error, ResumableUploadLedger] =
    readOptional(path).flatMap(_.toRight(Error.Missing(key)))

  private def readOptional(path: Path): Either[Error, Option[ResumableUploadLedger]] =
    if !Files.exists(path, LinkOption.NOFOLLOW_LINKS) then Right(None)
    else read(path).map(Some(_))

  private def read(path: Path): Either[Error, ResumableUploadLedger] =
    try
      val size = Files.size(path)
      if size <= 0L || size > LedgerBytes.MaxBytes.toLong then
        Left(Error.Storage("read", new IllegalArgumentException(s"session ledger size $size is outside 1..${LedgerBytes.MaxBytes}")))
      else
        for
          bounded <- LedgerBytes
                       .fromArray(readExactlyBounded(path, size.toInt))
                       .left
                       .map(message => Error.Storage("read", new IllegalArgumentException(message)))
          decoded <- ApiJson
                       .decode[WireLedger](new String(bounded.value, StandardCharsets.UTF_8))
                       .left
                       .map(message => Error.Storage("decode", new IllegalArgumentException(message)))
          ledger  <- decoded.toDomain.left.map(message => Error.Storage("decode", new IllegalArgumentException(message)))
        yield ledger
    catch case error: Throwable => Left(Error.Storage("read", error))

  /**
   * Read only the size already proven below the compile-time ledger ceiling.
   * The trailing-byte probe closes the Files.size/read time-of-check gap.
   */
  private def readExactlyBounded(path: Path, expectedBytes: Int): Array[Byte] =
    val result  = new Array[Byte](expectedBytes)
    val channel = FileChannel.open(path, StandardOpenOption.READ)
    try
      val buffer = ByteBuffer.wrap(result)
      while buffer.hasRemaining do
        if channel.read(buffer) < 0 then throw new IllegalStateException(s"session ledger ended before its declared $expectedBytes bytes")
      if channel.read(ByteBuffer.allocate(1)) >= 0 then
        throw new IllegalStateException(s"session ledger grew beyond its bounded $expectedBytes bytes while being read")
      result
    finally channel.close()

  private def write(path: Path, ledger: ResumableUploadLedger): Either[Error, Unit] =
    try
      val encoded = ApiJson.encode(WireLedger.fromDomain(ledger)).getBytes(StandardCharsets.UTF_8)
      LedgerBytes.fromArray(encoded) match
        case Left(message) => Left(Error.Storage("encode", new IllegalArgumentException(message)))
        case Right(_)      =>
          Files.createDirectories(path.getParent)
          val tmp = Files.createTempFile(path.getParent, ".session-", ".tmp")
          try
            Files.write(tmp, encoded, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)
            forceFile(tmp)
            Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            forceDirectory(path.getParent)
            Right(())
          finally
            val _ = Files.deleteIfExists(tmp)
    catch case error: Throwable => Left(Error.Storage("write", error))

  private def fromBlocking[A](operation: String)(value: => A): IO[Error, A] =
    ZIO.attemptBlocking(value).mapError(error => Error.Storage(operation, error))

  private def ledgerFiles: ZStream[Any, Throwable, Path] =
    ZStream.unwrap {
      ZIO.attemptBlocking(Files.exists(base, LinkOption.NOFOLLOW_LINKS)).map {
        case false => ZStream.empty
        case true  =>
          ZStream
            .acquireReleaseWith(ZIO.attemptBlocking(Files.walk(base)))(paths =>
              graviton.runtime.lifecycle.ResourceFinalizer.closeBlocking("resumable-upload filesystem walk")(paths.close())
            )
            .flatMap(paths => ZStream.fromIterator(paths.iterator().asScala))
            .filter(path => Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && path.getFileName.toString.endsWith(".json"))
      }
    }

  private def forceFile(path: Path): Unit =
    val channel = FileChannel.open(path, StandardOpenOption.WRITE)
    try channel.force(true)
    finally channel.close()

  private def forceDirectory(path: Path): Unit =
    val channel = FileChannel.open(path, StandardOpenOption.READ)
    try channel.force(true)
    finally channel.close()

object FsResumableUploadRepository:
  /** Fixed-cardinality local serialization for the single-node filesystem backend. */
  private val SessionLockShards = 256

  type LedgerBytes = LedgerBytes.T
  object LedgerBytes extends RefinedTypeExt[Array[Byte], MinLength[1] & MaxLength[16777216]]:
    inline val MaxBytes                                            = 16777216
    def fromArray(value: Array[Byte]): Either[String, LedgerBytes] = either(value)

  private final case class WireLocator(scheme: String, bucket: String, path: String)
  private object WireLocator:
    given BlocksSchema[WireLocator]                                          = BlocksSchema.derived
    def fromDomain(value: BlobLocator): WireLocator                          = WireLocator(value.scheme.value, value.bucket.value, value.path.value)
    extension (value: WireLocator) def toDomain: Either[String, BlobLocator] = BlobLocator.from(value.scheme, value.bucket, value.path)

  private final case class WirePart(
    id: String,
    number: Int,
    offset: Long,
    size: Long,
    locator: WireLocator,
  )
  private object WirePart:
    given BlocksSchema[WirePart]                         = BlocksSchema.derived
    def fromDomain(value: ResumableUploadPart): WirePart =
      WirePart(value.id.value, value.number.value, value.offset.value, value.size.value, WireLocator.fromDomain(value.locator))
    extension (value: WirePart)
      def toDomain: Either[String, ResumableUploadPart]  =
        for
          id      <- UploadPartId.either(value.id)
          number  <- UploadPartNumber.either(value.number)
          offset  <- UploadOffset.either(value.offset)
          size    <- FileSize.either(value.size)
          locator <- value.locator.toDomain
        yield ResumableUploadPart(id, number, offset, size, locator)

  private final case class WireReservation(
    partId: String,
    number: Int,
    offset: Long,
    locator: WireLocator,
    leaseId: String,
    leaseExpiresAt: Long,
  )
  private object WireReservation:
    given BlocksSchema[WireReservation]                                          = BlocksSchema.derived
    def fromDomain(value: UploadPartReservation): WireReservation                =
      WireReservation(
        value.partId.value,
        value.number.value,
        value.offset.value,
        WireLocator.fromDomain(value.locator),
        value.leaseId.value,
        value.leaseExpiresAt.toEpochMilli,
      )
    extension (value: WireReservation)
      def toDomain(key: UploadSessionKey): Either[String, UploadPartReservation] =
        for
          partId  <- UploadPartId.either(value.partId)
          number  <- UploadPartNumber.either(value.number)
          offset  <- UploadOffset.either(value.offset)
          locator <- value.locator.toDomain
          leaseId <- UploadLeaseId.either(value.leaseId)
        yield UploadPartReservation(key, partId, number, offset, locator, leaseId, Instant.ofEpochMilli(value.leaseExpiresAt))

  private final case class WireCommitLease(id: String, expiresAt: Long)
  private object WireCommitLease:
    given BlocksSchema[WireCommitLease] = BlocksSchema.derived

  private final case class WireLedger(
    tenantId: String,
    sessionId: String,
    contentType: String,
    expectedSize: Option[Long],
    offset: Long,
    partCount: Int,
    createdAt: Long,
    expiresAt: Long,
    phase: String,
    committedBlob: Option[String],
    parts: List[WirePart],
    reservations: List[WireReservation],
    commitLease: Option[WireCommitLease],
  ):
    def toDomain: Either[String, ResumableUploadLedger] =
      for
        tenant        <- TenantId.either(tenantId)
        upload        <- UploadSessionId.either(sessionId)
        key            = UploadSessionKey(tenant, upload)
        mediaType     <- MediaTypeText.parse(contentType)
        expected      <- expectedSize match
                           case None        => Right(None)
                           case Some(value) => FileSize.either(value).map(Some(_))
        refinedOffset <- UploadOffset.either(offset)
        refinedCount  <- UploadPartNumber.either(partCount)
        parsedPhase   <- ResumableUploadPhase.values.find(_.toString == phase).toRight(s"invalid upload phase '$phase'")
        blob          <- committedBlob match
                           case None        => Right(None)
                           case Some(value) => KeyBits.parse(value).flatMap(BinaryKey.blob).map(Some(_))
        parsedParts   <- traverse(parts)(_.toDomain)
        parsedLeases  <- traverse(reservations)(_.toDomain(key))
        lease         <- commitLease match
                           case None        => Right(None)
                           case Some(value) =>
                             UploadLeaseId
                               .either(value.id)
                               .map(id => Some(ResumableUploadLedger.CommitLease(id, Instant.ofEpochMilli(value.expiresAt))))
        session        = ResumableUploadSession(
                           key,
                           UploadIntent(mediaType, expected),
                           refinedOffset,
                           refinedCount,
                           Instant.ofEpochMilli(createdAt),
                           Instant.ofEpochMilli(expiresAt),
                           parsedPhase,
                           blob,
                         )
      yield ResumableUploadLedger(session, parsedParts.toVector, parsedLeases.map(value => value.partId -> value).toMap, lease)

  private object WireLedger:
    given BlocksSchema[WireLedger] = BlocksSchema.derived
    given ApiJsonCodec[WireLedger] = ApiJsonCodec.derived

    def fromDomain(value: ResumableUploadLedger): WireLedger =
      WireLedger(
        value.session.key.tenantId.value,
        value.session.key.uploadSessionId.value,
        value.session.intent.contentType.fullType,
        value.session.intent.expectedSize.map(_.value),
        value.session.offset.value,
        value.session.partCount.value,
        value.session.createdAt.toEpochMilli,
        value.session.expiresAt.toEpochMilli,
        value.session.phase.toString,
        value.session.committedBlob.map(_.bits.render),
        value.parts.map(WirePart.fromDomain).toList,
        value.reservations.valuesIterator.map(WireReservation.fromDomain).toList,
        value.commitLease.map(value => WireCommitLease(value.id.value, value.expiresAt.toEpochMilli)),
      )

  private def traverse[A, B](values: List[A])(f: A => Either[String, B]): Either[String, List[B]] =
    values.foldRight[Either[String, List[B]]](Right(Nil)) { (value, accumulated) =>
      for
        head <- f(value)
        tail <- accumulated
      yield head :: tail
    }
