package torrent

import zio.*
import zio.stream.*

import BinaryAttributes.{ withContentType, withLength }

/**
 * Represents binary data with its attributes
 */
final case class Binary(
  id:         BinaryKey,
  attributes: BinaryAttributes,
  data:       ByteStream
)

object Binary:

  /**
   * Creates a Binary from raw data with auto-generated ID
   */
  def fromBytes(
    bytes:       Chunk[Byte],
    contentType: MediaType = MediaType.application.octetStream
  ): UIO[Binary] =
    BinaryKey.random.map(
      Binary(
        _,
        BinaryAttributes.empty
          .withContentType(contentType)
          .withLength(bytes.size),
        ByteStream(ZStream.fromChunk(bytes))
      )
    )
