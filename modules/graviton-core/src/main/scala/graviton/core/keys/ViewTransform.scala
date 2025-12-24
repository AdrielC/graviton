package graviton.core
package keys

import zio.schema.{DeriveSchema, Schema}

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import scala.collection.immutable.ListMap

final case class ViewTransform(name: String, args: ListMap[String, String], scope: Option[String]):
  /**
   * Canonical arg ordering for deterministic view key derivation.
   *
   * Notes:
   * - args are treated as opaque strings; callers MUST avoid nondeterministic inputs
   *   (timestamps, random seeds, machine ids, "current", etc).
   * - values MUST already be normalized (no pretty JSON, stable numeric forms, etc).
   */
  def normalizedArgs: List[(String, String)] = args.toList.sortBy(_._1)

  /** Byte-level canonical encoding for hashing. */
  def canonicalBytes: Array[Byte] =
    ViewTransform.canonicalBytes(this)

object ViewTransform:

  // Guardrail: ban obvious nondeterministic argument keys from view identity.
  // This is intentionally small and conservative; add keys as you find offenders.
  private val bannedArgKeys: Set[String] =
    Set(
      "timestamp",
      "time",
      "now",
      "seed",
      "random",
      "nonce",
      "machine",
      "host",
      "hostname",
      "pid",
      "current_version",
      "currentversion",
    )

  def apply(name: String, args: Map[String, String], scope: Option[String]): ViewTransform =
    ViewTransform(name, ListMap(args.toList.sortBy(_._1)*), scope)

  def validateDeterministic(transform: ViewTransform): Either[String, ViewTransform] =
    val badKeys =
      transform.args.keysIterator.map(_.trim.toLowerCase).filter(bannedArgKeys.contains).toList.sorted

    if badKeys.nonEmpty then Left(s"Non-deterministic arg keys are forbidden in view transforms: ${badKeys.mkString(", ")}")
    else Right(transform)

  def canonicalBytes(transform: ViewTransform): Array[Byte] =
    // Length-prefixed UTF-8 fields, fixed ordering.
    // Format (v1):
    //   u8 version = 1
    //   name: u32 len + bytes
    //   scope: u8 present + (u32 len + bytes if present)
    //   args: u32 count + repeated (key, value) each (u32 len + bytes)
    val version: Byte = 1

    val args0 = transform.normalizedArgs

    def utf8(s: String): Array[Byte] =
      Option(s).getOrElse("").getBytes(StandardCharsets.UTF_8)

    def sized(bytes: Array[Byte]): Array[Byte] =
      val len = bytes.length
      val bb  = ByteBuffer.allocate(4 + len)
      bb.putInt(len)
      bb.put(bytes)
      bb.array()

    val nameBytes  = sized(utf8(transform.name))
    val scopeBytes =
      transform.scope match
        case None        => Array(0.toByte)
        case Some(value) => Array(1.toByte) ++ sized(utf8(value))

    val argsCount = ByteBuffer.allocate(4).putInt(args0.length).array()
    val argsBytes = args0.flatMap { case (k, v) => sized(utf8(k)) ++ sized(utf8(v)) }.toArray

    Array(version) ++ nameBytes ++ scopeBytes ++ argsCount ++ argsBytes

  given Schema[ViewTransform] = DeriveSchema.gen[ViewTransform]
