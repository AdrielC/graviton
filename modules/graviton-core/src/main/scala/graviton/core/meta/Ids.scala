package graviton.core.meta

import zio.schema.*

final case class NamespaceUrn private (value: String) extends AnyVal:
  override def toString: String = value

object NamespaceUrn:
  def from(value: String): Either[String, NamespaceUrn] =
    Option(value).map(_.trim) match
      case None | Some("") => Left("Namespace URN cannot be empty")
      case Some(text)      => Right(new NamespaceUrn(text))

  def unsafe(value: String): NamespaceUrn =
    from(value).fold(msg => throw new IllegalArgumentException(msg), identity)

  given Schema[NamespaceUrn] =
    Schema[String].transformOrFail(from, urn => Right(urn.value))

final case class SchemaId private (value: String) extends AnyVal:
  override def toString: String = value

object SchemaId:
  def from(value: String): Either[String, SchemaId] =
    Option(value).map(_.trim) match
      case None | Some("") => Left("Schema id cannot be empty")
      case Some(text)      => Right(new SchemaId(text))

  def unsafe(value: String): SchemaId =
    from(value).fold(msg => throw new IllegalArgumentException(msg), identity)

  given Schema[SchemaId] =
    Schema[String].transformOrFail(from, id => Right(id.value))

final case class SemVer private (major: Int, minor: Int, patch: Int):
  def repr: SemVerRepr = SemVerRepr.unsafe(s"$major.$minor.$patch")

  override def toString: String = repr.value

object SemVer:
  def make(major: Int, minor: Int, patch: Int): Either[String, SemVer] =
    if major < 0 || minor < 0 || patch < 0 then Left("SemVer values must be non-negative")
    else Right(new SemVer(major, minor, patch))

  def unsafe(major: Int, minor: Int, patch: Int): SemVer =
    make(major, minor, patch).fold(msg => throw new IllegalArgumentException(msg), identity)

  def parse(repr: SemVerRepr): Either[String, SemVer] =
    repr.value.split("\\.") match
      case Array(maj, min, patch) =>
        for
          major  <- parsePart("major", maj)
          minor  <- parsePart("minor", min)
          pat    <- parsePart("patch", patch)
          semVer <- make(major, minor, pat)
        yield semVer
      case _                      => Left(s"Invalid semantic version: ${repr.value}")

  private def parsePart(label: String, part: String): Either[String, Int] =
    part.toIntOption.toRight(s"Invalid $label version component: $part")

  given Schema[SemVer] = DeriveSchema.gen[SemVer]

final case class SemVerRepr private (value: String) extends AnyVal:
  override def toString: String = value

object SemVerRepr:
  private val SemVerRegex = """\d+\.\d+\.\d+""".r

  def from(value: String): Either[String, SemVerRepr] =
    Option(value).map(_.trim) match
      case None | Some("")                         => Left("SemVer representation cannot be empty")
      case Some(text) if SemVerRegex.matches(text) => Right(new SemVerRepr(text))
      case Some(text)                              => Left(s"Invalid semantic version string: $text")

  def unsafe(value: String): SemVerRepr =
    from(value).fold(msg => throw new IllegalArgumentException(msg), identity)

  given Schema[SemVerRepr] =
    Schema[String].transformOrFail(from, repr => Right(repr.value))

final case class TenantId private (value: String) extends AnyVal:
  override def toString: String = value

object TenantId:
  def from(value: String): Either[String, TenantId] =
    Option(value).map(_.trim) match
      case None | Some("") => Left("Tenant id cannot be empty")
      case Some(text)      => Right(new TenantId(text))

  def unsafe(value: String): TenantId =
    from(value).fold(msg => throw new IllegalArgumentException(msg), identity)

  given Schema[TenantId] =
    Schema[String].transformOrFail(from, id => Right(id.value))

final case class SystemId private (value: String) extends AnyVal:
  override def toString: String = value

object SystemId:
  def from(value: String): Either[String, SystemId] =
    Option(value).map(_.trim) match
      case None | Some("") => Left("System id cannot be empty")
      case Some(text)      => Right(new SystemId(text))

  def unsafe(value: String): SystemId =
    from(value).fold(msg => throw new IllegalArgumentException(msg), identity)

  given Schema[SystemId] =
    Schema[String].transformOrFail(from, id => Right(id.value))
