package torrent

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64

import scala.annotation.tailrec
import scala.util.control.NonFatal

import io.github.iltotore.iron.constraint.all.*
import io.github.iltotore.iron.{ :|, RefinedType, refineEither }
import torrent.schemas.RefinedTypeExt

import zio.json.{ JsonDecoder, JsonEncoder }
import zio.schema.*
import zio.schema.annotation.description
import zio.{ Chunk, Random as ZRandom, UIO }

sealed trait BinaryKey derives JsonEncoder, JsonDecoder:
  import BinaryKey.*
  def renderKey: String = this match
    case UUID(id)                => s"uuid://${id.toString}"
    case Hashed(hash, algorithm) => s"hash://${algorithm.canonicalName}/${hash}"
    case Static(name)            => s"user://$name"
    case Scoped(key, scope)      =>
      s"scoped://${Base64.getEncoder.encodeToString(scope.mkString("__").getBytes(StandardCharsets.UTF_8))}/${key.renderKey}"

object BinaryKey:

  inline transparent given schema: Schema[BinaryKey] = DeriveSchema.gen[BinaryKey]

  sealed trait Borrowed                                               extends BinaryKey
  private[torrent] case class Hashed(hash: Hash, algorithm: HashAlgo) extends Borrowed
  private[torrent] case class UUID(id: java.util.UUID)                extends Borrowed
  object UUID:
    type KeyRegex = "^uuid:[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$"
    given Schema[UUID]                          = Schema[String :| Match[KeyRegex]]
      .transformOrFail(a => UUID(a), _.toString.refineEither)
    given JsonEncoder[UUID]                     = JsonEncoder.string.contramap(_.toString)
    def apply(id: String): Either[String, UUID] =
      try Right(UUID(java.util.UUID.fromString(id)))
      catch case NonFatal(e) => Left(e.getMessage)
  sealed trait Owned                                                  extends BinaryKey
  private[torrent] case class Static(name: String)                    extends Owned
  private[torrent] case class Scoped(key: BinaryKey, scope: Scope)    extends Owned

  def random: UIO[BinaryKey.UUID] = ZRandom.nextUUID.map(UUID(_))

  type Hash = Hash.T
  object Hash extends RefinedTypeExt[String, HashDescription]:
    def apply(hash: MessageDigest): Either[String | Throwable, Hash] =
      try Bytes(hash.digest).map(_.toHex).flatMap(either)
      catch case NonFatal(e) => Left(e)

  type ScopePart = ScopePart.T
  object ScopePart extends RefinedTypeExt[String, ScopePartDescription]

  type Scope = Scope.T
  object Scope extends RefinedTypeExt[Map[ScopePart, List[ScopePart]], ScopeDescription]:
    val empty: Scope = either(Map(ScopePart("quasar://root") -> Nil))
      .getOrElse(sys.error("Scope should never be empty"))
    extension (scope: Scope)
      def toMap: Map[ScopePart, List[ScopePart]] = scope
      def mkString                               =
        val s: Map[ScopePart, List[ScopePart]] = scope
        s.values.map(_.sorted.mkString("[", ",", "]")).mkString("/")

  end Scope

  extension (key: BinaryKey)
    @tailrec
    def value: Chunk[Byte] =
      key match
        case UUID(id)        => Chunk.fromArray(id.toString.getBytes(StandardCharsets.UTF_8))
        case Hashed(hash, _) => Chunk.fromArray(hash.getBytes(StandardCharsets.UTF_8))
        case Static(name)    => Chunk.fromArray(name.getBytes(StandardCharsets.UTF_8))
        case Scoped(key, _)  => key.value

    def mkString: String =
      key match
        case UUID(id)                => s"uuid/${id.toString}"
        case Hashed(hash, algorithm) => s"cas/${algorithm.canonicalName}:$hash"
        case Static(name)            => s"user/$name"
        case Scoped(key, scope)      => s"scoped/${scope.mkString}${if scope.toMap.isEmpty then "" else "/"}${key.mkString}"

  enum KeyType[+A <: BinaryKey]:
    case Borrowed extends KeyType[Borrowed]
    case Owned    extends KeyType[Owned]

  enum KeyMatcher derives Schema:
    case MatchAll
    case Prefix(prefix: String, keyType: KeyType[BinaryKey])
    case Scoped(scopePrefix: Scope)
    private case And(left: KeyMatcher, right: KeyMatcher)
    private case Or(left: KeyMatcher, right: KeyMatcher)
    private case Not(matcher: KeyMatcher)

    infix def and(other: KeyMatcher): KeyMatcher = And(this, other)
    infix def or(other:  KeyMatcher): KeyMatcher = Or(this, other)
    infix def &&(other:  KeyMatcher): KeyMatcher = and(other)
    infix def ||(other:  KeyMatcher): KeyMatcher = or(other)
    def not: KeyMatcher                          = Not(this)
    def unary_! : KeyMatcher                     = not

    def matches(key: BinaryKey): Boolean =
      this match
        case MatchAll                       => true
        case Prefix(prefix, typ)            => key.mkString.startsWith(prefix)
        case KeyMatcher.Scoped(scopePrefix) =>
          key match
            case key: BinaryKey.Scoped =>
              val sMap = key.scope.toMap
              scopePrefix.toMap.forall { case (a, b) =>
                sMap.get(a).exists(l => b.forall(l.contains))
              }
            case _                     => false
        case And(left, right)               => left.matches(key) && right.matches(key)
        case Or(left, right)                => left.matches(key) || right.matches(key)
        case Not(matcher)                   => !matcher.matches(key)
  end KeyMatcher

  type HashDescription = DescribedAs[
    MinLength[32] & MaxLength[128] & Match["^[0-9a-f]+$"],
    "A lowercase hex string representing 16 to 64 bytes of raw binary data (MD5 through SHA-512)"
  ]

  type ScopeDescription = DescribedAs[
    MinLength[1] & MaxLength[500],
    "Scopes must be between 1 and 500 scope parts long"
  ]

  type ScopePartDescription = DescribedAs[
    MinLength[1] & MaxLength[500] & Match["^[a-zA-Z][a-zA-Z0-9+.-]*://[^\\s/$.?#][^\\s]*$"],
    "Size between 1 and 500 characters long and conforms to \"^[a-zA-Z][a-zA-Z0-9+.-]*://[^\\\\s/$.?#][^\\\\s]*$\""
  ]
