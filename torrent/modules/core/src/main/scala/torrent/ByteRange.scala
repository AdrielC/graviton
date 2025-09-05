package torrent

import zio.schema.{ DeriveSchema, Schema }

/**
 * Specifies a byte range in a binary file
 */
enum ByteRange:
  /**
   * The entire file
   */
  case Full

  /**
   * Take the first n bytes
   */
  case Take(n: Length)

  /**
   * Skip the first n bytes
   */
  case Drop(n: Length)

  /**
   * Take n bytes starting from a position
   */
  case Slice(from: Index, n: Length)

  /**
   * Take the last n bytes
   */
  case Last(n: Length)

  /**
   * Returns the start position of this range
   *
   * @param total
   *   Total length of the file
   */
  def start(total: Length): Index =
    this match
      case Full           => Index.min
      case Take(_)        => Index.min
      case Drop(n)        => n.toIndexExcl
      case Slice(from, _) => from
      case Last(n)        =>
        total.toIndexExcl
          .subtract(n.toIndexExcl)
          .map(t =>
            Ordering[Index]
              .max(Index.min, t.toIndexExcl)
          )
          .getOrElse(total.toIndex)

  /**
   * Returns the end position (exclusive) of this range
   *
   * @param total
   *   Total length of the file
   */
  def end(total: Length): Index =
    this match
      case Full           => total.toIndexExcl
      case Take(n)        => Ordering[Index].min(n.toIndex, total.toIndex)
      case Drop(_)        => total.toIndexExcl
      case Slice(from, n) => Ordering[Index].min(total.toIndexExcl, from.add(n.toIndexExcl))
      case Last(_)        => total.toIndexExcl

  /**
   * Returns the length of this range
   *
   * @param total
   *   Total length of the file
   */
  def length(total: Length): Option[Length] = Length.either(end(total) - start(total)).toOption

  /**
   * Is this range a full range (entire file)?
   */
  def isFull: Boolean = this == Full

object ByteRange:
  implicit val schema: Schema[ByteRange] = DeriveSchema.gen
