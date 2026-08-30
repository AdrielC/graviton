package graviton.runtime.config

import graviton.runtime.stores.{ManifestIntegrity, ManifestKeyId, ManifestKeyService}
import zio.{Chunk, Config, IO, ZIO}

import java.util.Base64
import scala.util.Try

/** Keyed manifest authentication. Disabled is the compatibility default. */
final case class ManifestIntegrityConfig private (
  required: Boolean,
  keyId: ManifestKeyId,
  private val keys: Map[ManifestKeyId, ManifestKeyService.HmacKey],
):
  override def toString: String = s"ManifestIntegrityConfig(required=$required,keyId=${keyId.value},keys=<redacted:${keys.size}>)"

  def build: IO[String, Option[ManifestIntegrity]] =
    if !required then ZIO.none
    else
      ZIO
        .fromOption(keys.get(keyId))
        .orElseFail("manifest integrity is required but no HMAC key was configured")
        .zipRight(ZIO.fromEither(ManifestKeyService.hmac(keyId, keys)).map(service => Some(ManifestIntegrity(service))))

object ManifestIntegrityConfig:
  val DefaultKeyId: ManifestKeyId       = ManifestKeyId.applyUnsafe("primary")
  val Disabled: ManifestIntegrityConfig = ManifestIntegrityConfig(false, DefaultKeyId, Map.empty)

  val config: Config[ManifestIntegrityConfig] =
    (Config.boolean("required").withDefault(false) ++
      Config.string("key-id").withDefault(DefaultKeyId.value) ++
      Config.string("hmac-key-base64").optional ++
      Config.string("previous-keys-base64").optional)
      .mapOrFail { case (required, rawKeyId, rawSecret, rawPrevious) =>
        (for
          keyId    <- ManifestKeyId.either(rawKeyId)
          active   <- decodeOptional(rawSecret, "manifest HMAC key")
          previous <- decodePrevious(rawPrevious)
          _        <- Either.cond(!previous.contains(keyId), (), "previous manifest keys must not repeat the active key ID")
          keys      = active.fold(previous)(value => previous.updated(keyId, value))
          _        <- Either.cond(!required || keys.contains(keyId), (), "hmac-key-base64 is required when manifest integrity is required")
        yield ManifestIntegrityConfig(required, keyId, keys)).left
          .map(message => Config.Error.InvalidData(Chunk.empty, message))
      }
      .nested("manifest-integrity")
      .nested("graviton")

  private def decodeOptional(
    value: Option[String],
    label: String,
  ): Either[String, Option[ManifestKeyService.HmacKey]] =
    value.map(_.trim).filter(_.nonEmpty) match
      case None        => Right(None)
      case Some(value) => decodeKey(value, label).map(Some(_))

  private def decodePrevious(
    value: Option[String]
  ): Either[String, Map[ManifestKeyId, ManifestKeyService.HmacKey]] =
    value.map(_.trim).filter(_.nonEmpty).fold[Either[String, Map[ManifestKeyId, ManifestKeyService.HmacKey]]](Right(Map.empty)) { encoded =>
      encoded
        .split(',')
        .iterator
        .map(_.trim)
        .filter(_.nonEmpty)
        .foldLeft[Either[String, Map[ManifestKeyId, ManifestKeyService.HmacKey]]](Right(Map.empty)) { (result, entry) =>
          result.flatMap { keys =>
            val separator = entry.indexOf(':')
            for
              _     <- Either.cond(separator > 0 && separator < entry.length - 1, (), "previous manifest keys must use key-id:base64")
              keyId <- ManifestKeyId.either(entry.substring(0, separator))
              _     <- Either.cond(!keys.contains(keyId), (), s"duplicate previous manifest key ID '${keyId.value}'")
              key   <- decodeKey(entry.substring(separator + 1), s"previous manifest key '${keyId.value}'")
            yield keys.updated(keyId, key)
          }
        }
    }

  private def decodeKey(value: String, label: String): Either[String, ManifestKeyService.HmacKey] =
    Try(Base64.getDecoder.decode(value)).toEither.left
      .map(_ => s"$label is not valid base64")
      .flatMap(ManifestKeyService.HmacKey.fromBytes)
