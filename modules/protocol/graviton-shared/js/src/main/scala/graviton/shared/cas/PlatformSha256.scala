package graviton.shared.cas

import graviton.shared.cas.ContentAddressing.*

import scala.scalajs.js
import scala.scalajs.js.typedarray.*
import scala.concurrent.{ExecutionContext, Future}

private[cas] object PlatformSha256:
  def digest(bytes: InteractiveBytes): Future[Sha256Hex] =
    given ExecutionContext = ExecutionContext.parasitic

    webCrypto match
      case Left(error)   => Future.failed(error)
      case Right(crypto) =>
        try
          crypto.subtle
            .digest("SHA-256", toUint8Array(bytes))
            .asInstanceOf[js.Promise[ArrayBuffer]]
            .toFuture
            .recoverWith { case _ => Future.failed(ContentAddressingError.CryptoFailure("Web Crypto")) }
            .flatMap { buffer =>
              Sha256Hex
                .either(toHex(new Uint8Array(buffer)))
                .fold(
                  _ => Future.failed(ContentAddressingError.InvalidDigest()),
                  Future.successful,
                )
            }
        catch case _: Throwable => Future.failed(ContentAddressingError.CryptoFailure("Web Crypto"))

  private def webCrypto: Either[ContentAddressingError, js.Dynamic] =
    val crypto = js.Dynamic.global.selectDynamic("crypto")
    Either.cond(
      !js.isUndefined(crypto) && crypto != null && !js.isUndefined(crypto.selectDynamic("subtle")),
      crypto,
      ContentAddressingError.CryptoUnavailable(),
    )

  private def toUint8Array(bytes: InteractiveBytes): Uint8Array =
    val array = new Uint8Array(bytes.length)
    bytes.zipWithIndex.foreach { case (byte, index) =>
      array(index) = (byte & 0xff).toShort
    }
    array

  private def toHex(bytes: Uint8Array): String =
    (0 until bytes.length).iterator
      .map(index => f"${bytes(index).toInt & 0xff}%02x")
      .mkString
