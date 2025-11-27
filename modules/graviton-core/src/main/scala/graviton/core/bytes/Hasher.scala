package graviton.core.bytes

import java.security.{MessageDigest, Provider}
import scala.util.control.NonFatal

trait Hasher:
  def algo: HashAlgo
  def update(chunk: Array[Byte]): Hasher
  def result: Either[String, Digest]

private final class MessageDigestHasher(private val md: MessageDigest, val algo: HashAlgo) extends Hasher:
  override def update(chunk: Array[Byte]): Hasher =
    md.update(chunk)
    this

  override def result: Either[String, Digest] =
    val hex = md.digest().map(b => f"${b & 0xff}%02x").mkString
    Digest.make(algo, hex)

object Hasher:

  def messageDigest(algo: HashAlgo, provider: Option[Provider] = None): Either[String, Hasher] =
    val attempts = algo.jceNames.map { name =>
      instantiate(name, provider).map(md => new MessageDigestHasher(md, algo)).left.map(name -> _)
    }

    attempts.collectFirst { case Right(hasher) => hasher }.toRight {
      val diagnostics = attempts.collect { case Left((candidate, err)) => s"$candidate -> ${err.getMessage}" }
      val names       = algo.jceNames.mkString(", ")
      val detail      = if diagnostics.nonEmpty then diagnostics.mkString("; ") else "no provider feedback"
      s"No MessageDigest provider found for ${algo.toString} (tried: $names). Details: $detail"
    }

  inline def unsafeMessageDigest(algo: HashAlgo, provider: Option[Provider] = None): Hasher =
    messageDigest(algo, provider).fold(msg => throw IllegalStateException(msg), identity)

  private def instantiate(name: String, provider: Option[Provider]): Either[Throwable, MessageDigest] =
    try
      val md =
        provider match
          case Some(explicit) => MessageDigest.getInstance(name, explicit)
          case None           => MessageDigest.getInstance(name)
      Right(md)
    catch case NonFatal(err) => Left(err)
