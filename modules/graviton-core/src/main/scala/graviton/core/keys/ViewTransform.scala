package graviton.core
package keys

import zio.schema.{DeriveSchema, Schema}

import graviton.core.canonical.CanonicalEncoding
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
    CanonicalEncoding.ViewTransformV1.encode(this)

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

  given Schema[ViewTransform] = DeriveSchema.gen[ViewTransform]
