package graviton.crypto

import zio.*
import zio.Chunk

final case class SymmetricKey(id: Chunk[Byte], algo: String, keyBytes: Chunk[Byte])

trait KeyStore:
  def getKey(id: Chunk[Byte]): IO[Throwable, Option[SymmetricKey]]
  def defaultKey: IO[Throwable, Option[SymmetricKey]]

object KeyStore:
  def getKey(id: Chunk[Byte]): ZIO[KeyStore, Throwable, Option[SymmetricKey]] =
    ZIO.serviceWithZIO[KeyStore](_.getKey(id))

  def defaultKey: ZIO[KeyStore, Throwable, Option[SymmetricKey]] =
    ZIO.serviceWithZIO[KeyStore](_.defaultKey)

final case class InMemoryKeyStore(keys: Map[String, SymmetricKey], default: Option[SymmetricKey]) extends KeyStore:
  def getKey(id: Chunk[Byte]): IO[Throwable, Option[SymmetricKey]] =
    ZIO.succeed(keys.get(java.util.Base64.getUrlEncoder.withoutPadding().encodeToString(id.toArray)))
  def defaultKey: IO[Throwable, Option[SymmetricKey]]              = ZIO.succeed(default)

object InMemoryKeyStore:
  def layer(keys: Map[String, SymmetricKey], default: Option[SymmetricKey]): ULayer[KeyStore] =
    ZLayer.succeed(InMemoryKeyStore(keys, default))
