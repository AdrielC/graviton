package graviton

import zio.*
import javax.crypto.*
import javax.crypto.spec.{GCMParameterSpec, SecretKeySpec}
import zio.Chunk

trait Encryption:
  def encrypt(hash: Hash, data: Chunk[Byte]): Task[Chunk[Byte]]
  def decrypt(hash: Hash, data: Chunk[Byte]): Task[Chunk[Byte]]

object Encryption:

  final case class MasterKey(bytes: Chunk[Byte])

  private class AesGcm(master: Chunk[Byte]) extends Encryption:

    private def deriveKey(hash: Hash): Array[Byte] =
      val mac = Mac.getInstance("HmacSHA256")
      val keySpec = new SecretKeySpec(master.toArray, "HmacSHA256")
      mac.init(keySpec)
      val prk = mac.doFinal(hash.bytes.toArray)
      java.util.Arrays.copyOf(prk, 32)

    private def nonce(hash: Hash): Array[Byte] =
      hash.bytes.take(12).toArray

    def encrypt(hash: Hash, data: Chunk[Byte]): Task[Chunk[Byte]] =
      ZIO.attempt {
        val secret = new SecretKeySpec(deriveKey(hash), "AES")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val spec = new GCMParameterSpec(128, nonce(hash))
        cipher.init(Cipher.ENCRYPT_MODE, secret, spec)
        Chunk.fromArray(cipher.doFinal(data.toArray))
      }

    def decrypt(hash: Hash, data: Chunk[Byte]): Task[Chunk[Byte]] =
      ZIO.attempt {
        val secret = new SecretKeySpec(deriveKey(hash), "AES")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val spec = new GCMParameterSpec(128, nonce(hash))
        cipher.init(Cipher.DECRYPT_MODE, secret, spec)
        Chunk.fromArray(cipher.doFinal(data.toArray))
      }

  val live: ZLayer[MasterKey, Nothing, Encryption] =
    ZLayer.fromFunction { (mk: MasterKey) => new AesGcm(mk.bytes) }
