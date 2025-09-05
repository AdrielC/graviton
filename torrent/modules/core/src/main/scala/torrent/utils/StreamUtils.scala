package torrent
package utils

import java.security.MessageDigest

import zio.*
import zio.stream.*

/**
 * Utility functions for working with binary streams
 */
object StreamUtils:
  /**
   * Calculate a hash of a byte stream
   *
   * @param algorithm
   *   Hash algorithm (e.g., "SHA-256", "MD5")
   * @return
   *   A function that can be used with ZStream.transduce
   */
  def hashTransducer(algorithm: HashAlgo): ZSink[Any, Nothing, Byte, Byte, String] =
    ZSink
      .foldLeft[Byte, MessageDigest](MessageDigest.getInstance(algorithm.canonicalName)) { (digest, byte) =>
        digest.update(byte)
        digest
      }
      .map { digest =>
        digest.digest().map(b => String.format("%02x", Byte.box(b))).mkString
      }
      .flatMap { hash =>
        ZSink.fromZIO(ZIO.succeed(hash))
      }

  /**
   * Counts bytes in a stream and returns the total count
   */
  val countBytes: ZSink[Any, Nothing, Byte, Nothing, Long] =
    ZSink.count

  /**
   * Probes a stream for MIME type by reading the first few bytes
   *
   * @param bytes
   *   Stream to probe
   * @return
   *   Detected MIME type or application/octet-stream if unknown
   */
  def detectContentType(bytes: ZStream[Any, Throwable, Byte]): IO[Throwable, MediaType] =
    bytes.take(512).runCollect.map { bytes =>
      // Very basic detection based on magic numbers
      if bytes.isEmpty then MediaType.application.octetStream
      else if bytes.startsWith(Chunk(0x89, 0x50, 0x4e, 0x47)) then MediaType.image.png
      else if bytes.startsWith(Chunk(0xff, 0xd8, 0xff)) then MediaType.image.jpeg
      else if bytes.startsWith(Chunk(0x47, 0x49, 0x46)) then MediaType.image.gif
      else if bytes.startsWith(Chunk(0x25, 0x50, 0x44, 0x46)) then MediaType.application.pdf
      else if bytes.startsWith(Chunk(0x50, 0x4b, 0x03, 0x04)) then MediaType.application.zip
      else
        // Look for text content
        val isText = bytes.forall(b => b >= 32 && b <= 126 || b == '\n' || b == '\r' || b == '\t')
        if isText then MediaType.text.plain
        else MediaType.application.octetStream
    }

  /**
   * Converts a Throwable to a StorageError appropriate for storage operations
   */
  def handleStorageError[A](io: IO[Throwable, A]): IO[StorageError, A] =
    io.mapError(StorageError.ReadError.apply)
