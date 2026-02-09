package graviton.core.locator

import graviton.core.keys.BinaryKey
import graviton.core.types.{LocatorBucket, LocatorPath, LocatorScheme}

trait LocatorStrategy:
  def locate(key: BinaryKey): BlobLocator

final case class PrefixLocatorStrategy(prefix: LocatorPath, bucket: LocatorBucket) extends LocatorStrategy:
  // SAFETY: compile-time constant matching LocatorScheme constraint
  private val casScheme: LocatorScheme =
    LocatorScheme.applyUnsafe("cas")

  def locate(key: BinaryKey): BlobLocator =
    val digest = key.bits.digest.value
    val shard  = digest.grouped(2).take(2).mkString("/")
    // SAFETY: prefix and digest are pre-validated; composed path is non-empty, no whitespace
    val path   = LocatorPath.applyUnsafe(s"${prefix.value}/$shard/$digest")
    BlobLocator(casScheme, bucket, path)
