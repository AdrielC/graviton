package graviton.runtime.model

import graviton.core.RefinedTypeExt
import io.github.iltotore.iron.constraint.collection.{MaxLength, MinLength}
import io.github.iltotore.iron.constraint.numeric.{GreaterEqual, LessEqual}
import zio.Chunk

import java.nio.charset.StandardCharsets
import java.util.Base64

/** Opaque continuation token generated and interpreted by a storage backend. */
type InventoryCursor = InventoryCursor.T
object InventoryCursor extends RefinedTypeExt[String, MinLength[1] & MaxLength[16384]]:
  private val Encoder = Base64.getUrlEncoder.withoutPadding()
  private val Decoder = Base64.getUrlDecoder

  private[graviton] def encode(namespace: InventoryNamespace, anchor: String): Either[String, InventoryCursor] =
    either(s"${namespace.prefix}.${Encoder.encodeToString(anchor.getBytes(StandardCharsets.UTF_8))}")

  private[graviton] def decode(cursor: InventoryCursor, expected: InventoryNamespace): Either[String, String] =
    cursor.value.split("\\.", 2).toList match
      case prefix :: encoded :: Nil if prefix == expected.prefix =>
        try Right(new String(Decoder.decode(encoded), StandardCharsets.UTF_8))
        catch case _: IllegalArgumentException => Left("inventory cursor payload is not valid base64url")
      case prefix :: _ :: Nil                                    =>
        Left(s"inventory cursor belongs to '$prefix', expected '${expected.prefix}'")
      case _                                                     =>
        Left("inventory cursor is malformed")

private[graviton] enum InventoryNamespace(val prefix: String):
  case Filesystem extends InventoryNamespace("fs1")
  case PostgreSql extends InventoryNamespace("pg1")
  case S3         extends InventoryNamespace("s31")
  case InMemory   extends InventoryNamespace("mem1")
  case Manifest   extends InventoryNamespace("mf1")

type InventoryPageSize = InventoryPageSize.T
object InventoryPageSize extends RefinedTypeExt[Int, GreaterEqual[1] & LessEqual[1000]]:
  val Default: InventoryPageSize = applyUnsafe(100)
  val Maximum: InventoryPageSize = applyUnsafe(1000)

/** One bounded inventory page plus a backend-native continuation cursor. */
final case class InventoryPage[+A](
  items: Chunk[A],
  next: Option[InventoryCursor],
)

object InventoryPage:
  def checked[A](
    items: Chunk[A],
    next: Option[InventoryCursor],
    limit: InventoryPageSize,
  ): Either[String, InventoryPage[A]] =
    Either.cond(
      items.length <= limit.value,
      InventoryPage(items, next),
      s"inventory backend returned ${items.length} rows for a ${limit.value}-row page",
    )
