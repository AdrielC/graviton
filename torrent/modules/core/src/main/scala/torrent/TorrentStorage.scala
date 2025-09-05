package torrent

import zio.*
import zio.stream.*

/**
 * Core storage interface for the Torrent library
 *
 * Represents a storage backend that can read, write, and manage binary files
 * with associated metadata.
 */
trait TorrentStorage:
  /**
   * Store a file with the given metadata and content stream
   *
   * @param meta
   *   Metadata for the file
   * @param content
   *   Stream of file content bytes
   * @return
   *   The ID of the stored file or error
   */
  def write(meta: FileMeta, content: ZStream[Any, Throwable, Byte]): IO[StorageError, BinaryKey]

  /**
   * Retrieve a file's content by ID
   *
   * @param id
   *   ID of the file to retrieve
   * @return
   *   Stream of the file content
   */
  def read(id: BinaryKey): ZStream[Any, StorageError, Byte]

  /**
   * Delete a file by ID
   *
   * @param id
   *   ID of the file to delete
   * @return
   *   true if file was deleted, false if it didn't exist
   */
  def delete(id: BinaryKey): IO[StorageError, Boolean]

  /**
   * Retrieve a file's metadata by ID
   *
   * @param id
   *   ID of the file to retrieve metadata for
   * @return
   *   The file metadata if found
   */
  def stat(id: BinaryKey): IO[StorageError, Option[FileMeta]]

  /**
   * List all files
   *
   * @return
   *   Stream of file metadata
   */
  def list: ZStream[Any, StorageError, FileMeta]

  /**
   * Check if a file exists
   *
   * @param id
   *   ID of the file to check
   * @return
   *   true if file exists
   */
  def exists(id: BinaryKey): IO[StorageError, Boolean] =
    stat(id).map(_.isDefined)
