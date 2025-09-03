package graviton

import zio.*

/** Simple abstraction for caching binary content by [[Hash]]. Implementations
  * must verify that any cached data matches the requested hash before serving
  * it and fall back to the provided download effect when the cache is absent or
  * invalid.
  */
trait CacheStore:
  /** Retrieve data for the supplied [[Hash]].
    *
    * @param hash
    *   expected hash of the content
    * @param download
    *   effect producing the content when it is not cached or the cached value
    *   is invalid
    * @param useCache
    *   when `false`, caching is bypassed and `download` is always executed
    */
  def fetch(
      hash: Hash,
      download: => Task[Bytes],
      useCache: Boolean = true
  ): Task[Bytes]

  /** Remove any cached value associated with the supplied hash. */
  def invalidate(hash: Hash): UIO[Unit]
