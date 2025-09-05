package torrent
package collections

import zio.*

/**
 * Optimized sliding window for binary data processing
 *
 * This collection is specifically designed for:
 *   - Rolling hash calculations
 *   - Content-defined chunking
 *   - Pattern matching in binary streams
 *   - Efficient sliding operations with minimal allocations
 */
trait SlidingWindow[A]:

  /**
   * Current window size
   */
  def size: Int

  /**
   * Maximum window size
   */
  def maxSize: Int

  /**
   * Check if window is full
   */
  def isFull: Boolean = size == maxSize

  /**
   * Check if window is empty
   */
  def isEmpty: Boolean = size == 0

  /**
   * Add an element, potentially evicting the oldest if at capacity
   */
  def add(element: A): SlidingWindow[A]

  /**
   * Get the oldest element (head of window)
   */
  def head: Option[A]

  /**
   * Get the newest element (tail of window)
   */
  def tail: Option[A]

  /**
   * Get element at index (0 = oldest, size-1 = newest)
   */
  def apply(index: Int): Option[A]

  /**
   * Convert to list (oldest to newest)
   */
  def toList: List[A]

  /**
   * Convert to chunk
   */
  def toChunk: Chunk[A]

  /**
   * Fold over elements (oldest to newest)
   */
  def foldLeft[B](z: B)(f: (B, A) => B): B

  /**
   * Map over elements maintaining window structure
   */
  def map[B](f: A => B): SlidingWindow[B]

/**
 * Circular buffer implementation of sliding window Optimized for constant-time
 * operations
 */
class CircularSlidingWindow[A] private (
  private val buffer: Array[Any],
  private val start:  Int,
  private val length: Int,
  val maxSize:        Int
) extends SlidingWindow[A]:

  def size: Int = length

  def add(element: A): SlidingWindow[A] =
    if length < maxSize then
      // Window not full, just add
      val newBuffer = buffer.clone()
      newBuffer((start + length) % maxSize) = element
      CircularSlidingWindow(newBuffer, start, length + 1, maxSize)
    else
      // Window full, evict oldest and add newest
      val newBuffer = buffer.clone()
      val newStart  = (start + 1) % maxSize
      newBuffer((start + length) % maxSize) = element
      CircularSlidingWindow(newBuffer, newStart, length, maxSize)

  def head: Option[A] =
    if isEmpty then None
    else Some(buffer(start).asInstanceOf[A])

  def tail: Option[A] =
    if isEmpty then None
    else Some(buffer((start + length - 1) % maxSize).asInstanceOf[A])

  def apply(index: Int): Option[A] =
    if index < 0 || index >= length then None
    else Some(buffer((start + index) % maxSize).asInstanceOf[A])

  def toList: List[A] =
    (0 until length).map(i => buffer((start + i) % maxSize).asInstanceOf[A]).toList

  def toChunk: Chunk[A] =
    Chunk.fromIterable(toList)

  def foldLeft[B](z: B)(f: (B, A) => B): B =

    @scala.annotation.tailrec
    def loop(acc: B, index: Int): B =
      if index >= length then acc
      else loop(f(acc, buffer((start + index) % maxSize).asInstanceOf[A]), index + 1)

    loop(z, 0)

  def map[B](f: A => B): SlidingWindow[B] =
    val newBuffer = new Array[Any](maxSize)

    @scala.annotation.tailrec
    def loop(i: Int): CircularSlidingWindow[B] =
      if i < length then
        newBuffer(i) = f(buffer((start + i) % maxSize).asInstanceOf[A])
        loop(i + 1)
      else CircularSlidingWindow(newBuffer, 0, length, maxSize)

    loop(0)

object CircularSlidingWindow:
  def apply[A](maxSize: Int): SlidingWindow[A] =
    CircularSlidingWindow(new Array[Any](maxSize), 0, 0, maxSize)

  private def apply[A](buffer: Array[Any], start: Int, length: Int, maxSize: Int): CircularSlidingWindow[A] =
    new CircularSlidingWindow[A](buffer, start, length, maxSize)

/**
 * Specialized sliding window for bytes with rolling hash support
 */
class ByteSlidingWindow(
  private val buffer:   Array[Byte],
  private val start:    Int,
  private val length:   Int,
  override val maxSize: Int,
  val rollingHash:      Long = 0L,
  val polynomial:       Long = 0x3da3358b4dc173L
) extends SlidingWindow[Byte]:

  override def toString: String =
    s"ByteSlidingWindow(buffer=${Bytes(buffer).toOption.getOrElse(Bytes.applyUnsafe(Chunk())).asBase64String}, start=$start, length=$length, maxSize=$maxSize, rollingHash=$rollingHash, polynomial=$polynomial)"

  def size: Int = length

  /**
   * Get current rolling hash value
   */
  def hash: Long = rollingHash

  def add(element: Byte): ByteSlidingWindow =
    if length < maxSize then
      // Window not full, just add
      val newBuffer = buffer.clone()
      newBuffer((start + length) % maxSize) = element
      val newHash   = updateHashAdd(rollingHash, element)
      new ByteSlidingWindow(newBuffer, start, length + 1, maxSize, newHash, polynomial)
    else
      // Window full, evict oldest and add newest
      val newBuffer = buffer.clone()
      val evicted   = buffer(start)
      val newStart  = (start + 1) % maxSize
      newBuffer((start + length) % maxSize) = element
      val newHash   = updateHashSlide(rollingHash, evicted, element)
      new ByteSlidingWindow(newBuffer, newStart, length, maxSize, newHash, polynomial)

  def head: Option[Byte] =
    if isEmpty then None
    else Some(buffer(start))

  def tail: Option[Byte] =
    if isEmpty then None
    else Some(buffer((start + length - 1) % maxSize))

  def apply(index: Int): Option[Byte] =
    if index < 0 || index >= length then None
    else Some(buffer((start + index) % maxSize))

  def toList: List[Byte] =
    (0 until length).map(i => buffer((start + i) % maxSize)).toList

  def toChunk: Chunk[Byte] =
    @scala.annotation.tailrec
    def loop(i: Int, acc: ChunkBuilder[Byte]): Chunk[Byte] =
      if i < length then loop(i + 1, acc += buffer((start + i) % maxSize))
      else acc.result()

    loop(0, ChunkBuilder.make[Byte]())

  def foldLeft[B](z: B)(f: (B, Byte) => B): B =
    @scala.annotation.tailrec
    def loop(acc: B, i: Int): B =
      if i < length then loop(f(acc, buffer((start + i) % maxSize)), i + 1)
      else acc

    loop(z, 0)

  def map[B](f: Byte => B): SlidingWindow[B] =
    CircularSlidingWindow(maxSize)
      .foldLeft(CircularSlidingWindow[B](maxSize)) { (window, _) =>
        toList.foldLeft(window)((w, b) => w.add(f(b)))
      }

  /**
   * Update hash when adding a byte (window not full)
   */
  private def updateHashAdd(currentHash: Long, newByte: Byte): Long =
    currentHash * polynomial + (newByte & 0xff)

  /**
   * Update hash when sliding (evicting old, adding new)
   */
  private def updateHashSlide(currentHash: Long, evictedByte: Byte, newByte: Byte): Long =
    // Remove contribution of evicted byte
    val powerTerm      = fastPower(polynomial, length)
    val withoutEvicted = currentHash - (evictedByte & 0xff) * powerTerm
    // Add new byte
    withoutEvicted * polynomial + (newByte & 0xff)

  /**
   * Fast exponentiation for polynomial powers
   */
  private def fastPower(base: Long, exp: Int): Long =
    @scala.annotation.tailrec
    def loop(result: Long, b: Long, e: Int): Long =
      if e <= 0 then result
      else
        val newResult = if (e & 1) == 1 then result * b else result
        val newB      = b * b
        val newE      = e >> 1
        loop(newResult, newB, newE)
    loop(1L, base, exp)

object ByteSlidingWindow:
  def apply(maxSize: Int, polynomial: Long = 0x3da3358b4dc173L): ByteSlidingWindow =
    new ByteSlidingWindow(new Array[Byte](maxSize), 0, 0, maxSize, 0L, polynomial)

/**
 * Sliding window that maintains statistics
 */
class StatsSlidingWindow[A: Numeric] private (
  private val window: SlidingWindow[A],
  private val sum:    A,
  private val min:    Option[A],
  private val max:    Option[A]
) extends SlidingWindow[A]:

  private val numeric = summon[Numeric[A]]
  import numeric.*

  def size: Int                 = window.size
  def maxSize: Int              = window.maxSize
  override def isEmpty: Boolean = window.isEmpty
  override def isFull: Boolean  = window.isFull

  /**
   * Current sum of all elements
   */
  def getSum: A = sum

  /**
   * Current average (if non-empty)
   */
  def getAverage: Option[Double] =
    if isEmpty then None
    else Some(sum.toDouble / size)

  /**
   * Current minimum value
   */
  def getMin: Option[A] = min

  /**
   * Current maximum value
   */
  def getMax: Option[A] = max

  def add(element: A): StatsSlidingWindow[A] =
    val oldHead   = window.head
    val newWindow = window.add(element)

    // Update sum
    val newSum = oldHead match
      case Some(evicted) if window.isFull => sum - evicted + element
      case _                              => sum + element

    // Update min/max
    val newMin = min match
      case None             => Some(element)
      case Some(currentMin) => Some(numeric.min(currentMin, element))

    val newMax = max match
      case None             => Some(element)
      case Some(currentMax) => Some(numeric.max(currentMax, element))

    new StatsSlidingWindow(newWindow, newSum, newMin, newMax)

  def head: Option[A]                          = window.head
  def tail: Option[A]                          = window.tail
  def apply(index:   Int): Option[A]           = window.apply(index)
  def toList: List[A]                          = window.toList
  def toChunk: Chunk[A]                        = window.toChunk
  def foldLeft[B](z: B)(f: (B, A) => B): B     = window.foldLeft(z)(f)
  def map[B](f:      A => B): SlidingWindow[B] = window.map(f)

object StatsSlidingWindow:
  def apply[A: Numeric](maxSize: Int): StatsSlidingWindow[A] =
    val numeric = summon[Numeric[A]]
    new StatsSlidingWindow(
      CircularSlidingWindow[A](maxSize),
      numeric.zero,
      None,
      None
    )

/**
 * Extension methods for sliding windows
 */
extension [A](window: SlidingWindow[A])
  /**
   * Add multiple elements efficiently
   */
  def addAll(elements: Iterable[A]): SlidingWindow[A] =
    elements.foldLeft(window)(_.add(_))

  /**
   * Check if window contains an element
   */
  def contains(element: A): Boolean =
    window.toList.contains(element)

  /**
   * Find first element matching predicate
   */
  def find(predicate: A => Boolean): Option[A] =
    window.toList.find(predicate)

  /**
   * Check if any element matches predicate
   */
  def exists(predicate: A => Boolean): Boolean =
    window.toList.exists(predicate)

  /**
   * Count elements matching predicate
   */
  def count(predicate: A => Boolean): Int =
    window.toList.count(predicate)
