package graviton.runtime.stores

import graviton.core.RefinedTypeExt
import graviton.runtime.upload.TenantId
import io.github.iltotore.iron.constraint.collection.{MaxLength, MinLength}
import io.github.iltotore.iron.constraint.numeric.{GreaterEqual, LessEqual}
import zio.Chunk

/** One named owner of live heap in a transfer pipeline. */
type TransferComponent = TransferComponent.T
object TransferComponent extends RefinedTypeExt[String, MinLength[1] & MaxLength[120]]

/** A positive, bounded byte contribution to one transfer reservation. */
type TransferBytes = TransferBytes.T
object TransferBytes extends RefinedTypeExt[Long, GreaterEqual[1L] & LessEqual[1099511627776L]]

final case class TransferContribution(component: TransferComponent, bytes: TransferBytes)

/** Components compose first; the resulting conservative total is reserved once. */
final case class TransferFootprint private (
  contributions: Chunk[TransferContribution],
  totalBytes: Long,
):
  def ++(other: TransferFootprint): Either[TransferFootprint.Error, TransferFootprint] =
    TransferFootprint.combine(Chunk(this, other))

  def scaled(copies: Int, component: TransferComponent): Either[TransferFootprint.Error, TransferFootprint] =
    if copies < 1 then Left(TransferFootprint.Error.InvalidMultiplier(copies))
    else TransferFootprint.multiply(totalBytes, copies.toLong).flatMap(TransferFootprint.single(component, _))

object TransferFootprint:
  val MaximumBytes: Long = 1099511627776L

  sealed abstract class Error(message: String) extends Exception(message)
  object Error:
    final case class InvalidBytes(bytes: Long)      extends Error(s"transfer contribution must be within 1..$MaximumBytes, got $bytes")
    final case class InvalidMultiplier(copies: Int) extends Error(s"transfer footprint multiplier must be positive, got $copies")
    final case class Overflow()                     extends Error("transfer footprint exceeds the supported 1 TiB per-operation bound")

  val empty: TransferFootprint = TransferFootprint(Chunk.empty, 0L)

  def single(component: TransferComponent, bytes: Long): Either[Error, TransferFootprint] =
    TransferBytes
      .either(bytes)
      .left
      .map(_ => Error.InvalidBytes(bytes))
      .map(value => TransferFootprint(Chunk.single(TransferContribution(component, value)), value.value))

  def combine(footprints: Iterable[TransferFootprint]): Either[Error, TransferFootprint] =
    footprints.foldLeft[Either[Error, TransferFootprint]](Right(empty)) { (result, footprint) =>
      result.flatMap { current =>
        add(current.totalBytes, footprint.totalBytes)
          .map(total => TransferFootprint(current.contributions ++ footprint.contributions, total))
      }
    }

  private[stores] def add(left: Long, right: Long): Either[Error, Long] =
    try
      val total = java.lang.Math.addExact(left, right)
      if total > MaximumBytes then Left(Error.Overflow()) else Right(total)
    catch case _: ArithmeticException => Left(Error.Overflow())

  private[stores] def multiply(value: Long, copies: Long): Either[Error, Long] =
    try
      val total = java.lang.Math.multiplyExact(value, copies)
      if total > MaximumBytes then Left(Error.Overflow()) else Right(total)
    catch case _: ArithmeticException => Left(Error.Overflow())

/** Admission identity for a transfer. Tenant identity is server-owned. */
final case class TransferScope(tenantId: Option[TenantId], backend: StoreBackend)

object TransferScope:
  def backend(backend: StoreBackend): TransferScope = TransferScope(None, backend)

/** Backend heap allocated in addition to the canonical block owned by CAS. */
trait BlockTransferFootprint:
  def transferBackend: StoreBackend
  def blockWriteFootprint(maximumBlockBytes: Int): Either[TransferFootprint.Error, TransferFootprint]

object BlockTransferFootprint:
  private val GenericCopy = TransferComponent.applyUnsafe("backend-replay-copy")

  def backendOf(store: BlockStore): StoreBackend = store match
    case profiled: BlockTransferFootprint => profiled.transferBackend
    case _                                => StoreBackend.Runtime

  def writeOf(store: BlockStore, maximumBlockBytes: Int): Either[TransferFootprint.Error, TransferFootprint] =
    store match
      case profiled: BlockTransferFootprint => profiled.blockWriteFootprint(maximumBlockBytes)
      case _                                => TransferFootprint.single(GenericCopy, maximumBlockBytes.toLong)
