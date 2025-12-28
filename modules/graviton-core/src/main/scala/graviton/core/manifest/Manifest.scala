package graviton.core.manifest

import graviton.core.keys.BinaryKey
import graviton.core.ranges.Span
import graviton.core.types.{ManifestAnnotationKey, ManifestAnnotationValue}
import graviton.core.types.BlobOffset

/**
 * A single manifest span pointing at a content-addressed key.
 *
 * `annotations` are intentionally non-semantic:
 * - MUST NOT affect view/key derivation
 * - MUST NOT change storage semantics
 * - MUST be safe to drop without changing meaning
 *
 * If you need semantic flags (compression/encryption/layout), model them as typed fields.
 */
final case class ManifestEntry(
  key: BinaryKey,
  span: Span[BlobOffset],
  annotations: Map[ManifestAnnotationKey, ManifestAnnotationValue],
)

final case class Manifest(entries: List[ManifestEntry], size: Long)

object Manifest:

  def fromEntries(entries: List[ManifestEntry]): Either[String, Manifest] =
    validate(entries, expectedSize = None)

  def validate(manifest: Manifest): Either[String, Manifest] =
    validate(manifest.entries, expectedSize = Some(manifest.size))

  private def validate(entries: List[ManifestEntry], expectedSize: Option[Long]): Either[String, Manifest] =
    val ord       = summon[Ordering[BlobOffset]]
    val validated =
      entries.zipWithIndex.foldLeft[Either[String, (List[ManifestEntry], Option[BlobOffset])]](Right((Nil, None))) {
        case (acc, (entry, idx)) =>
          acc.flatMap { case (accumulated, previousEnd) =>
            val start = entry.span.startInclusive
            val end   = entry.span.endInclusive

            if ord.lt(end, start) then Left(s"Entry $idx has negative length: start=$start end=$end")
            else if previousEnd.exists(prior => ord.lteq(start, prior)) then
              val prior = previousEnd.get
              Left(s"Entries must be strictly increasing and non-overlapping; entry $idx starts at $start after $prior")
            else
              val computedEnd = end
              Right((accumulated :+ entry, Some(computedEnd)))
          }
      }

    validated.flatMap { case (ordered, lastEnd) =>
      val computedSize =
        lastEnd match
          case None      => Right(0L)
          case Some(end) =>
            try Right(math.addExact(end.value, 1L))
            catch case _: ArithmeticException => Left("Manifest size overflow")

      computedSize.flatMap { total =>
        expectedSize match
          case Some(size) if size != total =>
            Left(s"Manifest size $size does not match computed span coverage $total")
          case _                           => Right(Manifest(ordered, total))
      }
    }
