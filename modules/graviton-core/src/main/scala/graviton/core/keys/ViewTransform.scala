package graviton.core
package keys

import zio.schema.{DeriveSchema, Schema}

import graviton.core.canonical.CanonicalEncoding
import graviton.core.types.{ViewArgKey, ViewArgValue, ViewName, ViewScope}
import graviton.core.types.given
import scala.collection.immutable.ListMap

/**
 * A deterministic “view derivation” transform applied to a base key.
 *
 * This is part of hashed identity derivation, so the fields are intentionally constrained:
 * - names/keys are ASCII-safe and bounded
 * - values are bounded (but otherwise opaque)
 *
 * Semantics must be stable over time: treat `name` + `args` + `scope` as an API contract.
 */
final case class ViewTransform(
  name: ViewName,
  args: ListMap[ViewArgKey, ViewArgValue],
  scope: Option[ViewScope],
):
  /**
   * Canonical arg ordering for deterministic view key derivation.
   *
   * Notes:
   * - args are treated as opaque strings; callers MUST avoid nondeterministic inputs
   *   (timestamps, random seeds, machine ids, "current", etc).
   * - values MUST already be normalized (no pretty JSON, stable numeric forms, etc).
   */
  def normalizedArgs: List[(ViewArgKey, ViewArgValue)] =
    args.toList.sortBy(_._1.value)

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

  def from(
    name: String,
    args: Map[String, String],
    scope: Option[String],
  ): Either[String, ViewTransform] =
    for
      n <- ViewName.either(Option(name).getOrElse("").trim)
      a <- toArgs(args)
      s <- scope match
             case None        => Right(None)
             case Some(value) => ViewScope.either(value.trim).map(Some(_))
      vt = ViewTransform(n, a, s)
      _ <- validateDeterministic(vt)
    yield vt

  private def toArgs(args: Map[String, String]): Either[String, ListMap[ViewArgKey, ViewArgValue]] =
    val pairs = args.toList.sortBy(_._1)
    pairs.foldLeft[Either[String, ListMap[ViewArgKey, ViewArgValue]]](Right(ListMap.empty)) { case (acc, (k, v)) =>
      for
        m  <- acc
        kk <- ViewArgKey.either(Option(k).getOrElse("").trim)
        vv <- ViewArgValue.either(Option(v).getOrElse(""))
      yield m.updated(kk, vv)
    }

  def validateDeterministic(transform: ViewTransform): Either[String, ViewTransform] =
    val badKeys =
      transform.args.keysIterator.map(_.value.trim.toLowerCase).filter(bannedArgKeys.contains).toList.sorted

    if badKeys.nonEmpty then Left(s"Non-deterministic arg keys are forbidden in view transforms: ${badKeys.mkString(", ")}")
    else Right(transform)

  given Schema[ViewTransform] = DeriveSchema.gen[ViewTransform]
