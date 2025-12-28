package graviton.core.locator

import graviton.core.keys.BinaryKey
import graviton.core.types.{LocatorBucket, LocatorPath, LocatorScheme}

trait LocatorStrategy:
  def locate(key: BinaryKey): BlobLocator

final case class PrefixLocatorStrategy(prefix: LocatorPath, bucket: LocatorBucket) extends LocatorStrategy:
  private val casScheme: LocatorScheme =
    LocatorScheme.applyUnsafe("cas")

  def locate(key: BinaryKey): BlobLocator =
    val digest = key.bits.digest.value
    val shard  = digest.grouped(2).take(2).mkString("/")
    val path   = LocatorPath.applyUnsafe(s"${prefix.value}/$shard/$digest")
    BlobLocator(casScheme, bucket, path)
