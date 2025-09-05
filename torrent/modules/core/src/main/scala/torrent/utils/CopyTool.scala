package torrent
package utils

import torrent.BinaryKey.KeyMatcher.MatchAll
import torrent.StorageError.NotFound

import zio.*
import zio.metrics.*
import zio.metrics.MetricKeyType.Histogram
import zio.prelude.{ Commutative, Identity }
import zio.stream.*

/**
 * Utility for copying data between binary stores
 */
object CopyTool:
  /**
   * Statistics from a copy operation
   */
  final case class CopyStats(
    totalFiles:       Int,
    bytesTransferred: Long,
    errors:           Int,
    durationMillis:   Long
  )

  object CopyStats:
    val empty: CopyStats = CopyStats(0, 0, 0, 0)
    implicit object CommMonoid extends Commutative[CopyStats], Identity[CopyStats]:
      override val identity                                  = CopyStats.empty
      override def combine(x: => CopyStats, y: => CopyStats) = CopyStats(
        totalFiles = x.totalFiles + y.totalFiles,
        bytesTransferred = x.bytesTransferred + y.bytesTransferred,
        errors = x.errors + y.errors,
        durationMillis = x.durationMillis + y.durationMillis
      )

  /**
   * Copy all content from one store to another
   *
   * @param source
   *   Source binary store
   * @param target
   *   Target binary store
   * @param chunkSize
   *   Chunk size for listing IDs
   * @param maxConcurrent
   *   Maximum number of concurrent copy operations
   * @return
   *   Statistics about the copy operation
   */
  def copyAll(
    source:        BinaryStore,
    target:        BinaryStore,
    chunkSize:     Int = 1024 * 1024,
    maxConcurrent: Int = 4
  ): ZIO[Scope, StorageError, CopyStats] =
    ZIO.logSpan("copyAll"):
      for
        _         <- ZIO.logInfo(s"Starting copy from $source to $target with maxConcurrent=$maxConcurrent")
        startTime <- Clock.instant
        results   <- source
                       .listKeys(MatchAll)
                       .tap(id => ZIO.logDebug(s"Processing ID: $id"))
                       .mapZIOPar(maxConcurrent)(id => copyOne(source, target, id, chunkSize))
                       .runFoldZIO(CopyStats.empty): (stats, result) =>
                         result match
                           case Right((id, bytes)) =>
                             (ZIO
                               .logInfo(s"Copied $id, $bytes bytes")
                               .as:
                                 stats.copy(
                                   totalFiles = stats.totalFiles + 1,
                                   bytesTransferred = stats.bytesTransferred + bytes
                                 )
                             )
                               @@ copy_total_files.contramap(_ => 1)
                               @@ copy_bytes_transferred.contramap(_ => bytes)

                           case Left(id) =>
                             (ZIO
                               .logInfo(s"Failed to copy $id")
                               .as:
                                 stats.copy(
                                   totalFiles = stats.totalFiles + 1,
                                   errors = stats.errors + 1
                                 )
                             ) @@ copy_errors.contramap(_ => 1)

        endTime <- Clock.instant
        duration = java.time.Duration.between(startTime, endTime).toMillis
        stats    = results.copy(durationMillis = duration)
        _       <- ZIO.logInfo(
                     s"Copy complete: ${stats.totalFiles} files, " +
                       s"${stats.bytesTransferred} bytes, " +
                       s"${stats.errors} errors, " +
                       s"${stats.durationMillis}ms"
                   )
      yield stats

  /**
   * Copy a single file from one store to another
   *
   * @param source
   *   Source binary store
   * @param target
   *   Target binary store
   * @param id
   *   ID of file to copy
   * @return
   *   Either the ID and bytes transferred, or just the ID in case of failure
   */
  private def copyOne(
    source:    BinaryStore,
    target:    BinaryStore,
    id:        BinaryKey,
    chunkSize: Int
  ): ZIO[Scope, StorageError, Either[BinaryKey, (BinaryKey, Long)]] =
    target
      .insertChunks(BinaryAttributes.empty)
      .flatMap: sinkInsert =>
        ZIO.logAnnotate("fileId", id.toString)(
          ((for
            // Check if target already has the file
            exists <- target.exists(id)
            result <-
              if exists then ZIO.logDebug(s"File already exists, skipping").as(Right((id, 0L)))
              else
                // Get the binary
                for
                  targetSink <- target.insertWith(id)
                  _          <- ZIO.logDebug(s"Starting file copy")
                  binaryOpt  <- source
                                  .getBinary(id, ByteRange.Full)
                                  .asSome
                                  .catchSome:
                                    case _: NotFound => ZIO.none
                  result     <- (binaryOpt: Option[Binary]) match
                                  case Some(binary) =>
                                    val sink = id match
                                      case _: BinaryKey.Borrowed  => sinkInsert.zipPar(ZSink.count)
                                      case owned: BinaryKey.Owned => targetSink.as(id).zipPar(ZSink.count)

                                    val sinkLogged = sink.mapZIO(bytes =>
                                      (ZIO.logDebug(s"Copied $bytes bytes").as(bytes) @@
                                        copy_bytes_transferred.contramap((_: (BinaryKey, Long))._2))
                                        .as(bytes)
                                    )

                                    // Copy the binary
                                    ZStream
                                      .fromZIO(binary.data.rechunk(chunkSize).peel(sinkLogged))
                                      .flatMap: (h, rest) =>
                                        ZStream.succeed(h) ++
                                          ZStream
                                            .fromZIO(rest.peel(ZSink.head))
                                            .flatMap(_._1 match
                                              case Some(_) =>
                                                ZStream.fail(StorageError.WriteError(Throwable("Exceeded write limit")))
                                              case None    => ZStream.empty)
                                      .tap((exists, bytes) => ZIO.logDebug(s"Copied $bytes bytes, file exists: $exists"))
                                      .map(bytes => Right((id, bytes._2)))
                                      .runLast
                                      .map(_.getOrElse(Left(id)))
                                  case None         =>
                                    ZIO.logWarning(s"Source file not found").as(Left(id))
                yield result
          yield result)
            .catchAll: error =>
              ZIO.logError(s"Error copying: ${error.getMessage}").as(Left(id)) @@ copy_errors.contramap(_ => 1)
            .timed @@ copy_duration)
            .map(_._2)
        )

  // Metrics for copy operations
  val copy_total_files       = Metric.counter("torrent_copy_total_files")
  val copy_bytes_transferred = Metric.counter("torrent_copy_bytes_transferred")
  val copy_errors            = Metric.counter("torrent_copy_errors")
  def copy_duration[A]       = Metric
    .histogram("torrent_copy_duration_ms", Histogram.Boundaries.exponential(0, 3, 20))
    .contramap[(Duration, A)] { case (t, _) => t.toMillis.toDouble }
