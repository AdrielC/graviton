package torrent

import java.io.{ InputStream, OutputStream }
import java.nio.ByteBuffer
import java.nio.charset.{ CharacterCodingException, Charset, StandardCharsets }
import java.util.UUID

import scala.annotation.targetName

import io.github.iltotore.iron.constraint.collection.MaxLength
import io.github.iltotore.iron.constraint.numeric.{ Greater, GreaterEqual, LessEqual }
import io.github.iltotore.iron.given
import io.github.iltotore.iron.{ :|, RefinedType, refineUnsafe }
import scodec.bits.BitVector
import torrent.schemas.RefinedTypeExt

import zio.schema.Schema
import zio.stream.ZStream
import zio.{ Chunk, NonEmptyChunk }

type MaxChunkSize = MaxChunkSize.type
inline val MaxChunkSize = 1024 * 1024 * 10 // 10MB

type ValidChunkSizeDescription = Greater[0L] & LessEqual[MaxChunkSize]

type ValidChunkSize = ValidChunkSize.T
object ValidChunkSize extends RefinedTypeExt[Long, ValidChunkSizeDescription]:
  val min: ValidChunkSize                         = ValidChunkSize(1L)
  val max: ValidChunkSize                         = ValidChunkSize(MaxChunkSize)
  extension (size: ValidChunkSize) def toInt: Int = size.toInt

type Length = Length.T
object Length extends RefinedTypeExt[Long, Greater[0L]]:
  val min: Length = Length(1)
  val max: Length = Length(Long.MaxValue)
  extension (length: Length)
    def toIndex: Index     = Index.applyUnsafe(length - 1L)
    def toIndexExcl: Index = Index.applyUnsafe(length)

type Index = Index.T
object Index extends RefinedTypeExt[Long, GreaterEqual[0L]]:
  val min: Index = Index(0)
  val max: Index = Index(Long.MaxValue)
  extension (index: Index)
    def subtract(other: Index): Option[Length] = Length.either(math.max(0L, index - other)).toOption
    def add(other: Index): Index               =
      Index.applyUnsafe(if (index + other > max) && (index + other < index) then max else index + other)
    def toLength: Length                       = Length.applyUnsafe(index + 1L)
    def toLengthExcl: Length                   = Length.applyUnsafe(index)
    def increment: Option[Index]               = Some(index).filter(_ == max).map(_ + 1L).map(Index.applyUnsafe)
    def decrement: Option[Index]               = Some(index).filter(_ == min).map(_ - 1L).map(Index.applyUnsafe)

/**
 * The opaque type definition for Bytes
 */
type Bytes = Bytes.T

/**
 * An immutable sequence of bytes with efficient operations.
 *
 * This is an opaque type wrapper around `zio.Chunk[Byte]` that provides a rich
 * set of operations for working with binary data while maintaining the
 * performance characteristics of `Chunk`.
 *
 * Most operations follow Scala collections naming conventions, and the class
 * provides convenient methods for encoding/decoding common formats.
 */
object Bytes extends RefinedTypeExt[Chunk[Byte], MaxLength[MaxChunkSize]]:

  /**
   * Create a Bytes from a sequence of individual bytes
   */
  def apply(byte: Byte, bytes: Byte*): Bytes =
    applyUnsafe(byte +: Chunk.fromIterable(bytes))

  /**
   * Create a Bytes from an array
   */
  def apply(bytes: Array[Byte]): Either[String, Bytes] =
    either(Chunk.fromArray(bytes))

  /**
   * Create a Bytes from a ByteBuffer
   */
  def apply(buffer: ByteBuffer): Either[String, Bytes] =
    either(Chunk.fromByteBuffer(buffer))

  /**
   * Create a Bytes from any Iterable of Byte
   */
  def apply(bytes: Iterable[Byte]): Either[String, Bytes] =
    either(Chunk.fromIterable(bytes))

  /**
   * Create a Bytes from any Iterable of Byte
   */
  def apply(bytes: NonEmptyChunk[Byte]): Bytes =
    apply(bytes.head, bytes.tail*)

  /**
   * Create a Bytes from a BitVector
   */
  def fromBitVector(bits: BitVector): Either[String, Bytes] =
    apply(bits.toByteArray)

  /**
   * Create a Bytes from a UUID
   */
  def fromUUID(uuid: UUID): Bytes =
    val bytes = new Array[Byte](16)
    val bb    = ByteBuffer.wrap(bytes)
    bb.putLong(uuid.getMostSignificantBits)
    bb.putLong(uuid.getLeastSignificantBits)
    applyUnsafe(Chunk.fromArray(bytes))

  /**
   * Create a Bytes without copying the underlying array
   */
  def view(bytes: Array[Byte]): Either[String, Bytes] =
    apply(bytes)

  /**
   * Create a Bytes from a slice of an array without copying
   */
  def view(bytes: Array[Byte], offset: Int, length: Int): Either[String, Bytes] =
    // Create a slice of the array and then create a Chunk from it
    apply(Chunk.fromArray(java.util.Arrays.copyOfRange(bytes, offset, offset + length)))

  /**
   * Create a Bytes from a ByteBuffer without copying
   */
  def view(buffer: ByteBuffer): Either[String, Bytes] =
    val buf = buffer.duplicate()
    apply(
      if buf.hasArray then
        val offset = buf.arrayOffset + buf.position()
        val length = buf.remaining()
        java.util.Arrays.copyOfRange(buf.array(), offset, offset + length)
      else
        val arr = new Array[Byte](buf.remaining)
        buf.get(arr)
        arr
    )

  /**
   * Create a Bytes filled with a given value
   */
  def fill(size: Length)(value: Byte): Bytes =
    applyUnsafe(Chunk.fill(size.toInt)(value))

  /**
   * Create a Bytes filled with zeros
   */
  def low(size: Length): Bytes = fill(size)(0)

  /**
   * Create a Bytes filled with 0xFF
   */
  def high(size: Length): Bytes = fill(size)(-1)

  /**
   * Construct a Bytes with a 2's complement encoded byte
   */
  def fromByte(b: Byte): Bytes = applyUnsafe(Chunk.single(b))

  /**
   * Construct a Bytes with a 2's complement encoded short
   */
  def fromShort(s: Short, ordering: ByteOrdering = ByteOrdering.BigEndian): Bytes =
    val bytes  = new Array[Byte](2)
    val buffer = ByteBuffer.wrap(bytes)

    ordering match
      case ByteOrdering.BigEndian    => buffer.putShort(s)
      case ByteOrdering.LittleEndian => buffer.putShort(java.lang.Short.reverseBytes(s))

    applyUnsafe(Chunk.fromArray(bytes))

  /**
   * Construct a Bytes with a 2's complement encoded int
   */
  def fromInt(i: Int, ordering: ByteOrdering = ByteOrdering.BigEndian): Bytes =
    val bytes  = new Array[Byte](4)
    val buffer = ByteBuffer.wrap(bytes)

    ordering match
      case ByteOrdering.BigEndian    => buffer.putInt(i)
      case ByteOrdering.LittleEndian => buffer.putInt(Integer.reverseBytes(i))

    applyUnsafe(Chunk.fromArray(bytes))

  /**
   * Construct a Bytes with a 2's complement encoded long
   */
  def fromLong(l: Long, ordering: ByteOrdering = ByteOrdering.BigEndian): Bytes =
    val bytes  = new Array[Byte](8)
    val buffer = ByteBuffer.wrap(bytes)

    ordering match
      case ByteOrdering.BigEndian    => buffer.putLong(l)
      case ByteOrdering.LittleEndian => buffer.putLong(java.lang.Long.reverseBytes(l))

    applyUnsafe(Chunk.fromArray(bytes))

  /**
   * Construct a Bytes from a hex string
   */
  def fromHex(hex: String): Option[Bytes] =
    try
      // Remove any whitespace, 0x prefix, or underscores
      val cleaned = hex.replaceAll("\\s|_", "").stripPrefix("0x").stripPrefix("0X")

      // Handle odd length string by padding with a leading 0
      val paddedHex = if cleaned.length % 2 == 1 then "0" + cleaned else cleaned

      // Convert pairs of hex chars to bytes
      val bytes = new Array[Byte](paddedHex.length / 2)
      for i <- bytes.indices do
        val j = i * 2
        bytes(i) = Integer.parseInt(paddedHex.substring(j, j + 2), 16).toByte

      apply(Chunk.fromArray(bytes)).toOption

    catch case _: NumberFormatException => None

  /**
   * Construct a Bytes from a hex string or throw if invalid
   */
  def fromValidHex(hex: String): Either[String, Bytes] =
    fromHex(hex).toRight(s"Invalid hex string: $hex")

  /**
   * Encode a string to Bytes using the given charset
   */
  def encodeString(string: String)(using charset: Charset): Either[CharacterCodingException | String, Bytes] =
    try
      val encoder = charset.newEncoder()
      val buffer  = encoder.encode(java.nio.CharBuffer.wrap(string))
      Bytes.view(buffer)
    catch case e: CharacterCodingException => Left(e)

  /**
   * Encode a string to Bytes using UTF-8
   */
  def encodeUtf8(string: String): Either[CharacterCodingException | String, Bytes] =
    encodeString(string)(using StandardCharsets.UTF_8)

  /**
   * Encode a string to Bytes using ASCII
   */
  def encodeAscii(string: String): Either[CharacterCodingException | String, Bytes] =
    encodeString(string)(using StandardCharsets.US_ASCII)

  /**
   * Concatenate multiple Bytes instances
   */
  def concat(chunk: Bytes, chunks: Bytes*): Bytes =
    chunks.foldLeft(chunk)((a, b) => applyUnsafe(a ++ b))

  /**
   * Extension methods for the Bytes type
   */
  extension (bytes: Bytes)

    def computeHashKey(algorithm: HashAlgo = HashAlgo.Blake3): BinaryKey.Hashed =
      val hash = BinaryKey.Hash.applyUnsafe(algorithm.hash(bytes.toArray))
      BinaryKey.Hashed(hash, algorithm)

    /**
     * Get the size of this Bytes
     */
    def getSize: Length =
      Length.applyUnsafe((bytes: Chunk[Byte]).length)

    def asUtf8String: String = bytes.asString(StandardCharsets.UTF_8)
    def asString: String     = bytes.asBase64String

    /**
     * Alias for size
     */
    def getLength: Length = Length.applyUnsafe((bytes: Chunk[Byte]).length)

    /**
     * Check if this Bytes is empty
     */
    def isEmpty: Boolean = bytes.isEmpty

    /**
     * Check if this Bytes is non-empty
     */
    def nonEmpty: Boolean = bytes.nonEmpty

    /**
     * Get the byte at the specified index
     */
    def get(index: Index): Option[Byte] =
      if index >= getSize then None
      else Some(bytes(index.toInt))

    /**
     * Alias for get
     */
    def apply(index: Index): Option[Byte] =
      get(index)

    /**
     * Get the byte at the specified index or None if out of bounds
     */
    def lift(index: Index): Option[Byte] =
      if index >= 0 && index < getSize then Some(bytes(index.toInt))
      else None

    /**
     * Update a byte at the specified index
     */
    def update(index: Index, byte: Byte): Option[Bytes] =
      if index < 0 || index >= getSize then None
      // Take bytes before index, add the new byte, then add bytes after index
      else if index == 0 then drop(Length(1L)).map(_.prependByte(byte))
      else
        drop(index.toLength).map(dropped =>
          Length
            .either(index: Long)
            .toOption
            .map(take(_))
            .map(_.appendBytes(Bytes(byte)))
            .getOrElse(Bytes(byte))
            .appendBytes(dropped)
        )

    /**
     * Insert a byte at the specified index
     */
    def insert(index: Index, byte: Byte): Option[Bytes] =
      if index > getSize then None
      else
        drop(index.toLength).map: dropped =>
          applyUnsafe:
            Length
              .either((index: Long) + 1L)
              .toOption
              .map(a => take(a).appendBytes(Bytes(byte)))
              .getOrElse(Bytes(byte))
              .appendBytes(dropped)

    /**
     * Append another Bytes to this one
     */
    @targetName("++")
    def appendBytes(other: Bytes): Bytes = applyUnsafe((bytes: Chunk[Byte]) ++ (other: Chunk[Byte]))

    /**
     * Prepend a byte to this Bytes
     */
    def +:(byte: Byte): Bytes =
      applyUnsafe(byte +: (bytes: Chunk[Byte]))

    /**
     * Prepend a byte to this Bytes
     */
    def prependByte(byte: Byte): Bytes =
      applyUnsafe(byte +: (bytes: Chunk[Byte]))

    /**
     * Append a byte to this Bytes
     */
    def :+(byte: Byte): Bytes =
      applyUnsafe((bytes: Chunk[Byte]) :+ byte)

    /**
     * Prepend a byte to this Bytes
     */
    def appendByte(byte: Byte): Bytes =
      applyUnsafe((bytes: Chunk[Byte]) :+ byte)

    /**
     * Take the first n bytes
     */
    def take(n: Length): Bytes =
      if n >= getSize then bytes
      else applyUnsafe((bytes: Chunk[Byte]).take(n.toInt))

    /**
     * Take the last n bytes
     */
    def takeRight(n: Length): Bytes =
      if n >= getSize then bytes
      else applyUnsafe((bytes: Chunk[Byte]).takeRight(n.toInt))

    /**
     * Drop the first n bytes
     */
    def drop(n: Length): Option[Bytes] =
      if n >= getSize then None
      else either((bytes: Chunk[Byte]).drop(n.toInt)).toOption

    /**
     * Drop the last n bytes
     */
    def dropRight(n: Length): Option[Bytes] =
      if n >= getSize then None
      else either((bytes: Chunk[Byte]).dropRight(n.toInt)).toOption

    /**
     * Split this Bytes at the specified index
     */
    def splitAt(n: Index): (Option[Bytes], Option[Bytes]) =
      (Length.either(n).toOption.map(a => take(a)), drop(n.toLength))

    /**
     * Get a slice of this Bytes
     */
    def slice(from: Index, until: Index): Bytes =
      if from <= 0 && until >= getSize then bytes
      else
        drop(from.toLength).get.take(
          Length.applyUnsafe:
            math.max(until: Long, from: Long) -
              math.min(from: Long, until: Long)
        )

    /**
     * Fold left over the bytes
     */
    def foldLeft[A](z: A)(f: (A, Byte) => A): A =
      bytes.foldLeft(z)(f)

    /**
     * Fold right over the bytes
     */
    def foldRight[A](z: A)(f: (Byte, A) => A): A =
      bytes.foldRight(z)(f)

    /**
     * Apply a function to each byte
     */
    def foreach(f: Byte => Unit): Unit =
      bytes.foreach(f)

    /**
     * Check if this Bytes starts with another Bytes
     */
    def startsWith(prefix: Bytes): Boolean =
      if prefix.getSize > getSize then false
      else take(prefix.getSize) == prefix

    /**
     * Check if this Bytes ends with another Bytes
     */
    def endsWith(suffix: Bytes): Boolean =
      if suffix.getSize > getSize then false
      else takeRight(suffix.getSize) == suffix

    /**
     * Find the index of a byte sequence in this Bytes
     */
    def indexOfSlice(slice: Bytes, from: Index = Index(0)): Option[Index] =
      if from >= getSize then None
      else if slice.isEmpty then Some(from)
      else if from.toLength + slice.getSize > getSize then None
      else if drop(from.toLength).get.startsWith(slice) then Some(from)
      else indexOfSlice(slice, Index.applyUnsafe(from + 1L))

    /**
     * Check if this Bytes contains a slice
     */
    def containsSlice(slice: Bytes): Boolean = indexOfSlice(slice) >= Index(0)

    /**
     * Group this Bytes into chunks of the specified size
     */
    def grouped(chunkSize: Length): Iterator[Bytes] =
      if isEmpty then Iterator.empty
      else if getSize <= chunkSize then Iterator.single(bytes)
      else (bytes: Chunk[Byte]).grouped(chunkSize.toInt).map(chunk => applyUnsafe(chunk))

    /**
     * Get the first byte
     */
    def head: Option[Byte] =
      if isEmpty then None
      else Some(bytes.head)

    /**
     * Get the first byte as an Option
     */
    def headOption: Option[Byte] =
      if isEmpty then None else Some(bytes.head)

    /**
     * Get all bytes except the first
     */
    def tail: Option[Bytes] =
      drop(Length(1L))

    /**
     * Get all bytes except the last
     */
    def init: Option[Bytes] =
      dropRight(Length(1L))

    /**
     * Get the last byte
     */
    def last: Byte =
      bytes.last

    /**
     * Add padding to the right to reach the specified size
     */
    def padRight(size: Length, padding: Byte = 0): Bytes =
      if size <= bytes.getSize then bytes
      else bytes.appendBytes(Bytes.fill(Length.applyUnsafe((size: Long) - (bytes.getSize: Long)))(padding))

    /**
     * Add padding to the left to reach the specified size
     */
    def padLeft(size: Length, padding: Byte = 0): Bytes =
      if size <= bytes.getSize then bytes
      else Bytes.fill(Length.applyUnsafe((size: Long) - (bytes.getSize: Long)))(padding).appendBytes(bytes)

    /**
     * Apply a function to each byte and create a new Bytes
     */
    def map(f: Byte => Byte): Bytes =
      applyUnsafe(bytes.map(f))

    /**
     * Reverse the bytes
     */
    def reverse: Bytes = applyUnsafe(bytes.reverse)

    /**
     * Get a compact copy of this Bytes
     */
    def compact: Bytes = applyUnsafe(Chunk.fromArray(toArray))

    /**
     * Convert to an array of bytes
     */
    def toArray: Array[Byte] = bytes.toArray

    /**
     * Copy bytes to an array starting at the specified index
     */
    def copyToArray(xs: Array[Byte], start: Index): Long =
      val chunk: Chunk[Byte] = bytes
      chunk.copyToArray(xs, start.toInt, Int.MaxValue)

    /**
     * Copy to ByteBuffer
     */
    def copyToBuffer(buffer: ByteBuffer): Long =
      val count      = math.min(buffer.remaining(), getSize.toInt)
      val duplicated = toByteBuffer.duplicate()
      duplicated.limit(count)
      buffer.put(duplicated)
      count

    /**
     * Copy to OutputStream
     */
    def copyToStream(stream: OutputStream): Unit =
      bytes.foreach(b => stream.write(b.toInt))

    /**
     * Create an InputStream from this Bytes
     */
    def toInputStream: InputStream = new BytesInputStream(bytes)

    /**
     * Convert to a ByteBuffer
     */
    def toByteBuffer: ByteBuffer = ByteBuffer.wrap(toArray)

    /**
     * Convert to a BitVector
     */
    def toBitVector: BitVector = BitVector(toArray)

    /**
     * Convert to a hex string
     */
    def toHex: String =
      val sb = new StringBuilder(bytes.getSize.toInt * 2)
      bytes.foreach { b =>
        val hex = Integer.toHexString(b & 0xff)
        if hex.length == 1 then sb.append('0')
        sb.append(hex)
      }
      sb.toString

    /**
     * Convert to a binary string
     */
    def toBin: String =
      val sb = new StringBuilder(bytes.getSize.toInt * 8)
      bytes.foreach { b =>
        for i <- 7 to 0 by -1 do sb.append(if ((b >> i) & 1) == 1 then '1' else '0')
      }
      sb.toString

    /**
     * Convert to a base64 string
     */
    def toBase64: String =
      java.util.Base64.getEncoder.encodeToString(toArray)

    /**
     * Convert to a base64 URL-safe string
     */
    def toBase64Url: String =
      java.util.Base64.getUrlEncoder.encodeToString(toArray)

    /**
     * Convert to a byte
     */
    def toByte: Either[IllegalArgumentException, Byte] =
      if getSize > 1 then Left(IllegalArgumentException(s"Size too large for Byte: $getSize"))
      else bytes.headOption.toRight(IllegalArgumentException(s"Size too small for Byte: $getSize"))

    /**
     * Convert to a short
     */
    def toShort(ordering: ByteOrdering = ByteOrdering.BigEndian): Either[IllegalArgumentException, Short] =
      if getSize > 2 then Left(IllegalArgumentException(s"Size too large for Short: $getSize"))
      else
        val a = padLeft(Length(2L)).toArray
        Right:
          ordering match
            case ByteOrdering.BigEndian    =>
              ((a(0) & 0xff) << 8 | (a(1) & 0xff)).toShort
            case ByteOrdering.LittleEndian =>
              ((a(1) & 0xff) << 8 | (a(0) & 0xff)).toShort

    /**
     * Convert to an int
     */
    def toInt(ordering: ByteOrdering = ByteOrdering.BigEndian): Either[IllegalArgumentException, Long] =
      if getSize > 4 then Left(IllegalArgumentException(s"Size too large for Int: $getSize"))
      else
        Right:
          val a = padLeft(Length(4L)).toArray
          ordering match
            case ByteOrdering.BigEndian    =>
              (a(0) & 0xff) << 24 | (a(1) & 0xff) << 16 | (a(2) & 0xff) << 8 | (a(3) & 0xff)
            case ByteOrdering.LittleEndian =>
              (a(3) & 0xff) << 24 | (a(2) & 0xff) << 16 | (a(1) & 0xff) << 8 | (a(0) & 0xff)

    /**
     * Convert to a long
     */
    def toLong(ordering: ByteOrdering = ByteOrdering.BigEndian): Either[IllegalArgumentException, Long] =
      if getSize > 8 then Left(IllegalArgumentException(s"Size too large for Long: $getSize"))
      else
        val a = padLeft(Length(8)).toArray
        Right:
          ordering match
            case ByteOrdering.BigEndian    =>
              (a(0) & 0xffL) << 56 | (a(1) & 0xffL) << 48 | (a(2) & 0xffL) << 40 | (a(3) & 0xffL) << 32 |
                (a(4) & 0xffL) << 24 | (a(5) & 0xffL) << 16 | (a(6) & 0xffL) << 8 | (a(7) & 0xffL)
            case ByteOrdering.LittleEndian =>
              (a(7) & 0xffL) << 56 | (a(6) & 0xffL) << 48 | (a(5) & 0xffL) << 40 | (a(4) & 0xffL) << 32 |
                (a(3) & 0xffL) << 24 | (a(2) & 0xffL) << 16 | (a(1) & 0xffL) << 8 | (a(0) & 0xffL)

    /**
     * Convert to a UUID
     */
    def toUUID: Either[IllegalArgumentException, UUID] =
      if getSize != 16 then
        Left(IllegalArgumentException(s"Cannot convert Bytes of size $getSize to UUID; must be 16 bytes"))
      else
        val bb = toByteBuffer
        Right(new UUID(bb.getLong, bb.getLong))

    /**
     * Decode as a string using the given charset
     */
    def decodeString(using charset: Charset): Either[CharacterCodingException, String] =
      try
        val decoder = charset.newDecoder()
        Right(decoder.decode(toByteBuffer).toString)
      catch case e: CharacterCodingException => Left(e)

    /**
     * Decode as a UTF-8 string
     */
    def decodeUtf8: Either[CharacterCodingException, String] =
      decodeString(using StandardCharsets.UTF_8)

    /**
     * Decode as an ASCII string
     */
    def decodeAscii: Either[CharacterCodingException, String] =
      decodeString(using StandardCharsets.US_ASCII)

    /**
     * Decode as a UTF-8 string ignoring errors
     */
    def decodeUtf8Lenient: String =
      new String(toArray, StandardCharsets.UTF_8)

    /**
     * Bitwise not
     */
    def unary_~ : Bytes =
      applyUnsafe(map(b => (~b: Long).toByte))

    /**
     * Bitwise or
     */
    def |(other: Bytes): Bytes =
      applyUnsafe:
        zipWith(other)((a, b) => (a | b).toByte)

    /**
     * Bitwise and
     */
    def &(other: Bytes): Bytes =
      applyUnsafe:
        zipWith(other)((a, b) => (a & b).toByte)

    /**
     * Bitwise xor
     */
    def ^(other: Bytes): Bytes =
      applyUnsafe:
        zipWith(other)((a, b) => (a ^ b).toByte)

    /**
     * Combine two Bytes with a function
     */
    def zipWith(other: Bytes)(f: (Byte, Byte) => Byte): Bytes =
      val len    = math.min(getSize.toInt, other.getSize.toInt)
      val result = new Array[Byte](len)

      for i <- 0 until len do result(i) = f(bytes(i), other(i))

      Bytes.view(result).getOrElse(bytes)

    /**
     * Convert to a ZStream of bytes
     */
    def toZStream: ZStream[Any, Nothing, Byte] =
      ZStream.fromChunk(bytes)

    /**
     * Compare two Bytes lexicographically
     */
    def compare(that: Bytes): Int =

      @scala.annotation.tailrec
      def loop(that: Bytes, index: Index): Int =
        val thisLen = bytes.getSize
        val thatLen = that.getSize
        if index >= thisLen || index >= thatLen then (thisLen - thatLen).toInt
        else
          val cmp = (bytes(index).get & 0xff) - (that(index).get & 0xff)
          if cmp != 0 then cmp
          else loop(that, Index.applyUnsafe(index + 1L))

      if this eq that then 0
      else loop(that, Index(0))

  // Provide type class instance for Ordering
  given Ordering[Bytes] = new:
    override def compare(x: Bytes, y: Bytes): Int = x.compare(y)

/**
 * Byte ordering for numeric conversions
 */
enum ByteOrdering:
  case BigEndian, LittleEndian

/**
 * InputStream implementation that reads from Bytes
 */
private class BytesInputStream(bytes: Chunk[Byte]) extends InputStream:
  private var position: Int :| Greater[-1L] = 0

  override def read(): Int =
    if position >= bytes.size then -1
    else
      val result = bytes(position) & 0xff
      position = (position + 1).refineUnsafe[Greater[-1L]]
      result

  override def read(b: Array[Byte], off: Int, len: Int): Int =
    if position >= bytes.size then -1
    else
      val available   = bytes.size - position
      val bytesToRead = math.min(len, available)

      for i <- 0 until bytesToRead do b(off + i) = bytes(position + i)

      position = (position + bytesToRead).refineUnsafe[Greater[0L]]
      bytesToRead

  override def available(): Int = bytes.size - position
