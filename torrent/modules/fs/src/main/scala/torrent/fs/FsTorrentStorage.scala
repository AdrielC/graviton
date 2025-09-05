package torrent.fs

import java.io.IOException

import torrent.*
import torrent.Attribute as BinaryAttribute

import zio.*
import zio.json.*
import zio.nio.file.{ Files, Path }
import zio.schema.*
import zio.stream.*

/**
 * File system implementation of TorrentStorage
 *
 * Stores files on the local file system with a configurable root directory.
 * Metadata is stored as JSON files alongside the binary content.
 */
class FsTorrentStorage(rootDir: Path) extends TorrentStorage:
  // Ensure root directory exists
  private val initRoot: IO[StorageError, Unit] =
    Files
      .createDirectories(rootDir)
      .mapError(e => StorageError.WriteError(e))
      .unit
      .memoize
      .flatMap(identity)

  /**
   * Get path for a file with the given ID
   */
  private def contentPath(id: BinaryKey): Path =
    rootDir / id.mkString

  /**
   * Get path for metadata file with the given ID
   */
  private def metaPath(id: BinaryKey): Path =
    rootDir / s"${id.mkString}.meta.json"

  /**
   * Read metadata from file
   */
  private def readMeta(id: BinaryKey): IO[StorageError, Option[FileMeta]] =
    val path = metaPath(id)
    Files.exists(path).flatMap {
      case false => ZIO.none
      case true  =>
        Files
          .readAllBytes(path)
          .map(bytes => new String(bytes.toArray, "UTF-8"))
          .map(json => summon[JsonDecoder[FileMeta]].decodeJson(json).toOption)
          .mapError(StorageError.ReadError.apply)
    }

  /**
   * Write metadata to file
   */
  private def writeMeta(meta: FileMeta): IO[StorageError, Unit] =
    val path               = metaPath(meta.id)
    val json               = summon[JsonEncoder[FileMeta]].encodeJson(meta, None)
    val jsonString: String = json.toString
    Files
      .writeBytes(path, Chunk.fromArray(jsonString.getBytes("UTF-8")))
      .mapError(StorageError.WriteError.apply)
      .unit

  override def write(meta: FileMeta, content: ZStream[Any, Throwable, Byte]): IO[StorageError, BinaryKey] =
    for
      _          <- initRoot
      // First write content so we can calculate actual size
      path        = contentPath(meta.id)
      _          <- Files
                      .writeBytes(path, Chunk.empty)
                      .zipRight(
                        content
                          .run(
                            ZSink.fromFile(path.toFile)
                          )
                      )
                      .mapError(StorageError.WriteError.apply)
      // Get file size
      size       <- Files.size(path).mapError(StorageError.WriteError.apply)
      // Update metadata with correct size
      updatedMeta = meta.copy(size = Some(size))
      // Write metadata
      _          <- writeMeta(updatedMeta)
    yield meta.id

  override def read(id: BinaryKey): ZStream[Any, StorageError, Byte] =
    ZStream
      .fromZIO(
        Files.exists(contentPath(id))
      )
      .flatMap {
        case false => ZStream.fail(StorageError.NotFound(id))
        case true  =>
          ZStream
            .fromFile(contentPath(id).toFile)
            .mapError {
              case e: IOException => StorageError.ReadError(e)
              case e: Throwable   => StorageError.ReadError(e)
            }
      }

  override def delete(id: BinaryKey): IO[StorageError, Boolean] =
    for
      // Check if file exists
      exists <- Files.exists(contentPath(id))
      result <-
        if exists then
          (for
            _ <- Files.delete(contentPath(id))
            _ <- Files.deleteIfExists(metaPath(id))
          yield true).mapError(e => StorageError.DeleteError(id, e))
        else ZIO.succeed(false)
    yield result

  override def stat(id: BinaryKey): IO[StorageError, Option[FileMeta]] =
    readMeta(id)

  override def list: ZStream[Any, StorageError, FileMeta] =
    ZStream.fromZIO(initRoot) *>
      Files
        .list(rootDir)
        .mapError(StorageError.ReadError.apply)
        .filter(path => !path.toString.endsWith(".meta.json"))
        .map(path => BinaryKey.Static(path.filename.toString))
        .flatMap(id => ZStream.fromZIO(stat(id).someOrFail(StorageError.NotFound(id))).orElse(ZStream.empty))

object FsTorrentStorage:
  /**
   * Create a FsTorrentStorage directly
   */
  def apply(rootDir: Path): FsTorrentStorage = new FsTorrentStorage(rootDir)

  /**
   * Create a layer for FsTorrentStorage
   */
  def layer(rootDir: Path): ZLayer[Any, StorageError, TorrentStorage] =
    ZLayer.fromZIO(
      Files
        .createDirectories(rootDir)
        .mapError(StorageError.WriteError.apply)
        .as(new FsTorrentStorage(rootDir))
    )

  given Schema[Map[String, DynamicValue]] = Schema[String]
    .zip(BinaryAttribute.codecs.schema)
    .repeated
    .transform(
      _.toMap,
      s => Chunk.fromIterable(s.toSeq)
    )
