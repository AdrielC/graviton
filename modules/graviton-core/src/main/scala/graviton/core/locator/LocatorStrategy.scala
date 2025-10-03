package graviton.core.locator

import graviton.core.keys.BinaryKey

trait LocatorStrategy:
  def locate(key: BinaryKey): BlobLocator

final case class PrefixLocatorStrategy(prefix: String, bucket: String) extends LocatorStrategy:
  def locate(key: BinaryKey): BlobLocator =
    val digest = key.bits.digest.value
    val shard  = digest.grouped(2).take(2).mkString("/")
    BlobLocator("cas", bucket, s"$prefix/$shard/$digest")
