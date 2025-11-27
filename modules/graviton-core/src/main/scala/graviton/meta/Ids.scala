package graviton.meta

import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.all.*
import scala.math.Ordering

import zio.schema.{DeriveSchema, Schema}

type NamespaceUrn = String :| NamespaceUrn.Constraint
object NamespaceUrn:
  type Constraint = Match["urn:[A-Za-z0-9][A-Za-z0-9:./_-]*"]

  def apply(value: String): Either[String, NamespaceUrn] = value.refineEither[Constraint]

  given Schema[NamespaceUrn] =
    Schema[String].transformOrFail(
      value => value.refineEither[Constraint],
      urn => Right(urn.asInstanceOf[String]),
    )

type SchemaId = String :| SchemaId.Constraint
object SchemaId:
  type Constraint = Match["schema:[A-Za-z0-9][A-Za-z0-9:./_-]*"]

  def apply(value: String): Either[String, SchemaId] = value.refineEither[Constraint]

  given Schema[SchemaId] =
    Schema[String].transformOrFail(
      value => value.refineEither[Constraint],
      id => Right(id.asInstanceOf[String]),
    )

type SemVerRepr = String :| SemVerRepr.Constraint
object SemVerRepr:
  type Constraint = Match["\\d+\\.\\d+\\.\\d+"]

  def apply(value: String): Either[String, SemVerRepr] = value.refineEither[Constraint]

  given Schema[SemVerRepr] =
    Schema[String].transformOrFail(
      value => value.refineEither[Constraint],
      repr => Right(repr.asInstanceOf[String]),
    )

final case class SemVer(major: Int, minor: Int, patch: Int):
  def repr: SemVerRepr =
    s"$major.$minor.$patch".refineUnsafe[SemVerRepr.Constraint]

object SemVer:
  def parse(value: String): Either[String, SemVer] =
    value.split("\\.") match
      case Array(major, minor, patch) =>
        try Right(SemVer(major.toInt, minor.toInt, patch.toInt))
        catch case _: NumberFormatException => Left(s"Invalid semantic version: $value")
      case _                          => Left(s"Invalid semantic version: $value")

  given Ordering[SemVer] = Ordering.by(ver => (ver.major, ver.minor, ver.patch))

  given Schema[SemVer] = DeriveSchema.gen[SemVer]

final case class SemVerRange(minimum: SemVer, maximum: SemVer):
  private val ordering = summon[Ordering[SemVer]]
  require(!ordering.gt(minimum, maximum), s"Invalid range: ${minimum.repr}..${maximum.repr}")

object SemVerRange:
  given Schema[SemVerRange] = DeriveSchema.gen[SemVerRange]
