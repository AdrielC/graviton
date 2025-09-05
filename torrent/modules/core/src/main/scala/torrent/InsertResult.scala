package torrent

import zio.schema.*

/**
 * Optional declared values to validate against during upload
 */
final case class ProvidedBinaryAttributes(
  claimedLength:    Option[Long] = None,
  claimedHash:      Option[(HashAlgo, DigestHex)] = None,
  claimedMediaType: Option[MediaTypeInstance] = None
)

object ProvidedBinaryAttributes:
  given Schema[ProvidedBinaryAttributes] = DeriveSchema.gen

  val empty: ProvidedBinaryAttributes = ProvidedBinaryAttributes()

/**
 * Rich result of a binary insert operation
 *
 * @param key
 *   The FileKey identifying the stored file
 * @param blobKeys
 *   List of BlobKeys (single for whole file, multiple for composite)
 * @param mediaType
 *   The inferred or provided media type
 * @param size
 *   Actual byte length of the file
 * @param hash
 *   Full-file hash digest
 * @param wasDeduplicated
 *   Whether this content already existed (CAS hit)
 * @param provided
 *   What the user originally provided for validation
 * @param validated
 *   Whether all provided validations passed
 * @param transformSource
 *   Optional source FileKey if this is a derived file
 * @param transformKind
 *   Optional transform type if this is a derived file
 */
final case class InsertResult(
  key:             FileKey,
  blobKeys:        List[BlobKey],
  mediaType:       MediaTypeInstance,
  size:            Long,
  hash:            DigestHex,
  wasDeduplicated: Boolean,
  provided:        ProvidedBinaryAttributes,
  validated:       Boolean,
  transformSource: Option[FileKey] = None,
  transformKind:   Option[String] = None
):
  def isWholeFile: Boolean = blobKeys.length == 1
  def isComposite: Boolean = blobKeys.length > 1
  def isDerived: Boolean   = transformSource.isDefined

object InsertResult:
  given Schema[InsertResult] = DeriveSchema.gen
