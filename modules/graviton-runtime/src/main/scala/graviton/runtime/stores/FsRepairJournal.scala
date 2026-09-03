package graviton.runtime.stores

import graviton.core.bytes.{Digest, HashAlgo}
import graviton.core.keys.{BinaryKey, KeyBits}
import zio.*
import zio.stream.ZStream

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, LinkOption, Path, StandardCopyOption, StandardOpenOption}
import java.time.Instant
import java.util.Base64

/** Filesystem journal using atomic cursor replacement and one bounded record per failed block. */
final class FsRepairJournal(root: Path) extends RepairJournal:
  private val directory    = root.resolve("cas/repair")
  private val cursorPath   = directory.resolve("cursor")
  private val failuresRoot = directory.resolve("dead-letters")

  override val loadCursor: IO[StoreError, Long] =
    ZIO
      .attemptBlocking {
        if !Files.exists(cursorPath, LinkOption.NOFOLLOW_LINKS) then 0L
        else
          val raw = Files.readString(cursorPath, StandardCharsets.US_ASCII).trim
          raw.toLongOption.filter(_ >= 0L).getOrElse(throw new IllegalStateException("repair cursor is corrupt"))
      }
      .mapError {
        case error: IllegalStateException => StoreError.CorruptData(StoreOperation.Repair, error.getMessage, error)
        case error                        => StoreError.fromThrowable(StoreOperation.Repair, StoreBackend.Filesystem)(error)
      }

  override def checkpoint(nextOffset: Long): IO[StoreError, Unit] =
    if nextOffset < 0L then ZIO.fail(StoreError.InvalidInput(StoreOperation.Repair, "repair cursor must be non-negative"))
    else atomicWrite(cursorPath, nextOffset.toString.getBytes(StandardCharsets.US_ASCII))

  override def recordFailure(key: BinaryKey.Block, error: StoreError, failedAt: Instant): IO[StoreError, Unit] =
    val path = failurePath(key)
    for
      previous <- readFailure(path, key)
      attempts  = previous.fold(1L)(entry => if entry.attempts == Long.MaxValue then Long.MaxValue else entry.attempts + 1L)
      detail    = Base64.getUrlEncoder.withoutPadding().encodeToString(RepairJournal.detail(error).getBytes(StandardCharsets.UTF_8))
      payload   = s"$attempts\t${failedAt.toEpochMilli}\t$detail\n".getBytes(StandardCharsets.US_ASCII)
      _        <- atomicWrite(path, payload)
    yield ()

  override def resolve(key: BinaryKey.Block): IO[StoreError, Unit] =
    ZIO
      .attemptBlocking(Files.deleteIfExists(failurePath(key)))
      .unit
      .mapError(StoreError.fromThrowable(StoreOperation.Repair, StoreBackend.Filesystem))

  override val deadLetters: ZStream[Any, StoreError, RepairDeadLetter] =
    FsBlobManifestRepo
      .walkFiles(failuresRoot)(path =>
        Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && path.getFileName.toString.endsWith(".failure")
      )
      .mapZIO { path =>
        ZIO
          .fromEither(keyFromFailurePath(path))
          .mapError(StoreError.CorruptData(StoreOperation.Repair, _))
          .flatMap(key => readFailure(path, key).someOrFail(StoreError.CorruptData(StoreOperation.Repair, s"empty repair record: $path")))
      }
      .mapError(StoreError.fromThrowable(StoreOperation.Repair, StoreBackend.Filesystem))

  override val healthCheck: IO[StoreError, Unit] =
    ZIO
      .attemptBlocking(Files.createDirectories(failuresRoot))
      .unit
      .mapError(StoreError.fromThrowable(StoreOperation.HealthCheck, StoreBackend.Filesystem))

  private def atomicWrite(path: Path, bytes: Array[Byte]): IO[StoreError, Unit] =
    ZIO
      .attemptBlocking {
        Files.createDirectories(path.getParent)
        val temporary = Files.createTempFile(path.getParent, ".repair-", ".tmp")
        try
          Files.write(temporary, bytes, StandardOpenOption.TRUNCATE_EXISTING)
          Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        finally
          val _ = Files.deleteIfExists(temporary)
      }
      .unit
      .mapError(StoreError.fromThrowable(StoreOperation.Repair, StoreBackend.Filesystem))

  private def readFailure(path: Path, key: BinaryKey.Block): IO[StoreError, Option[RepairDeadLetter]] =
    ZIO
      .attemptBlocking {
        if !Files.exists(path, LinkOption.NOFOLLOW_LINKS) then None
        else
          Files.readString(path, StandardCharsets.US_ASCII).trim.split("\\t", 3).toList match
            case attempts :: epochMillis :: detail :: Nil =>
              val decoded = new String(Base64.getUrlDecoder.decode(detail), StandardCharsets.UTF_8)
              Some(RepairDeadLetter(key, attempts.toLong, decoded, Instant.ofEpochMilli(epochMillis.toLong)))
            case _                                        => throw new IllegalStateException(s"repair record is corrupt: $path")
      }
      .mapError {
        case error: IllegalStateException => StoreError.CorruptData(StoreOperation.Repair, error.getMessage, error)
        case error                        => StoreError.fromThrowable(StoreOperation.Repair, StoreBackend.Filesystem)(error)
      }

  private def failurePath(key: BinaryKey.Block): Path =
    failuresRoot
      .resolve(key.bits.algo.primaryName.toLowerCase.replace("-", ""))
      .resolve(s"${key.bits.digest.hex.value}-${key.bits.size}.failure")

  private def keyFromFailurePath(path: Path): Either[String, BinaryKey.Block] =
    val algorithm = Option(path.getParent).flatMap(parent => Option(parent.getFileName)).map(_.toString).getOrElse("")
    val fileName  = path.getFileName.toString.stripSuffix(".failure")
    val separator = fileName.lastIndexOf('-')
    for
      _      <- Either.cond(separator > 0, (), s"invalid repair record path: $path")
      digest <- Digest.fromString(fileName.substring(0, separator))
      size   <- fileName.substring(separator + 1).toLongOption.toRight(s"invalid repair record size: $path")
      algo   <- HashAlgo.values.find(_.primaryName.toLowerCase.replace("-", "") == algorithm).toRight(s"invalid repair algorithm: $path")
      bits   <- KeyBits.fromLong(algo, digest, size)
      block  <- BinaryKey.block(bits)
    yield block
