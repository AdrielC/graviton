package graviton.shared.cas

import graviton.shared.cas.ContentAddressing.*

import java.security.MessageDigest
import scala.concurrent.Future

private[cas] object PlatformSha256:
  def digest(bytes: InteractiveBytes): Future[Sha256Hex] =
    try
      // `InteractiveBytes` proves this bounded conversion cannot exceed 8 KiB.
      val digest = MessageDigest.getInstance("SHA-256").digest(bytes.toArray)
      val hex    = digest.iterator.map(byte => f"${byte & 0xff}%02x").mkString
      Sha256Hex
        .either(hex)
        .fold(
          _ => Future.failed(ContentAddressingError.InvalidDigest()),
          Future.successful,
        )
    catch case _: Throwable => Future.failed(ContentAddressingError.CryptoFailure("JCA"))
