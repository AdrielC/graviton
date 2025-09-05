package torrent

import zio.schema.*

/**
 * High-level identity of a logical file Content-addressable and immutable
 */
sealed trait FileKey:
  def mkString: String

object FileKey:
  given Schema[FileKey] = DeriveSchema.gen[FileKey]

  /**
   * File stored as a single blob
   */
  final case class WholeFileHash(blobKey: BlobKey) extends FileKey:
    def mkString: String = s"whole:${blobKey.mkString}"

  /**
   * File stored as multiple chunks
   */
  final case class CompositeHash(chunks: List[BlobKey], digest: DigestHex) extends FileKey:
    def mkString: String = s"composite:${digest.value}:${chunks.length}"

  /**
   * File derived from another file through transformation
   */
  final case class Derived(base: FileKey, transform: Transform) extends FileKey:
    def mkString: String = s"derived:${base.mkString}:${transform.mkString}"

/**
 * Declarative transform lineage info
 */
final case class Transform(kind: String, params: Map[String, String]):
  def mkString: String =
    val paramStr = params.map { case (k, v) => s"$k=$v" }.mkString(",")
    if paramStr.nonEmpty then s"$kind($paramStr)" else kind

object Transform:
  given Schema[Transform] = DeriveSchema.gen[Transform]

  def redaction(regions: List[String]): Transform =
    Transform("redacted", Map("regions" -> regions.mkString(";")))

  def ocr(language: String = "eng"): Transform =
    Transform("ocr", Map("lang" -> language))

  def convert(targetFormat: String): Transform =
    Transform("convert", Map("format" -> targetFormat))
