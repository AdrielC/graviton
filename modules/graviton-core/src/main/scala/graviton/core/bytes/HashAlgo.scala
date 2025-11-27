package graviton.core.bytes

import zio.schema.DeriveSchema

enum HashAlgo(val hexLength: Int, val jceNames: List[String]) derives CanEqual:
  case Sha256 extends HashAlgo(64, List("SHA-256", "SHA256"))
  case Sha1   extends HashAlgo(40, List("SHA-1", "SHA1"))
  case Blake3 extends HashAlgo(64, List("BLAKE3-256", "BLAKE3"))

  lazy val primaryName: String = jceNames.head

object HashAlgo:
  given zio.schema.Schema[HashAlgo] = DeriveSchema.gen[HashAlgo]

  /** Preferred hash order (BLAKE3 first, then SHA-256 for FIPS environments, finally SHA-1 for legacy). */
  val preferredOrder: List[HashAlgo] = List(HashAlgo.Blake3, HashAlgo.Sha256, HashAlgo.Sha1)

  /** Primary build-time default (kept as BLAKE3 for performance). */
  val default: HashAlgo = HashAlgo.Blake3

  /** Detects the first available provider from [[preferredOrder]]. */
  lazy val runtimeDefault: HashAlgo =
    Hasher.acquirePreferred(preferredOrder).fold(_ => HashAlgo.Sha256, _._1)

  /** Convenience hook to obtain both the algorithm and the instantiated hasher following [[preferredOrder]]. */
  def runtimeHasher: Either[String, (HashAlgo, Hasher)] =
    Hasher.acquirePreferred(preferredOrder)

  def fromString(value: String): Option[HashAlgo] =
    value.toLowerCase match
      case "sha256" | "sha-256" => Some(HashAlgo.Sha256)
      case "sha1" | "sha-1"     => Some(HashAlgo.Sha1)
      case "blake3"             => Some(HashAlgo.Blake3)
      case _                    => None
