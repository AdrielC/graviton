package graviton

import zio.*
import zio.test.*
import graviton.kv.*

object KeyValueStoreSpec extends ZIOSpecDefault:
  def spec = suite("KeyValueStoreSpec")(
    test("put/getLatest/getLatestTimestamp basic flow") {
      val ns = "test"
      val k  = Chunk.fromArray("k".getBytes)
      val v1 = Chunk.fromArray("v1".getBytes)
      val v2 = Chunk.fromArray("v2".getBytes)
      val t1 = Timestamp(1L)
      val t2 = Timestamp(2L)
      for
        _   <- KeyValueStore.put(ns, k, v1, t1)
        _   <- KeyValueStore.put(ns, k, v2, t2)
        got <- KeyValueStore.getLatest(ns, k, None)
        ts  <- KeyValueStore.getLatestTimestamp(ns, k)
      yield assertTrue(got.contains(v2)) && assertTrue(ts.contains(t2))
    }.provideLayer(KeyValueStore.inMemory),
    test("getAllTimestamps returns all timestamps in insertion order not guaranteed") {
      val ns = "ts"
      val k  = Chunk.fromArray("k".getBytes)
      val v  = Chunk.fromArray("v".getBytes)
      val t1 = Timestamp(1L)
      val t2 = Timestamp(3L)
      val t3 = Timestamp(2L)
      for
        _   <- KeyValueStore.put(ns, k, v, t1)
        _   <- KeyValueStore.put(ns, k, v, t2)
        _   <- KeyValueStore.put(ns, k, v, t3)
        all <- KeyValueStore.getAllTimestamps(ns, k).runCollect
      yield assertTrue(all.toSet == Set(t1, t2, t3))
    }.provideLayer(KeyValueStore.inMemory),
    test("scanAll and scanAllKeys expose latest values for all keys") {
      val ns = "scan"
      val k1 = Chunk.fromArray("k1".getBytes)
      val k2 = Chunk.fromArray("k2".getBytes)
      val v1 = Chunk.fromArray("v1".getBytes)
      val v2 = Chunk.fromArray("v2".getBytes)
      for
        _    <- KeyValueStore.put(ns, k1, v1, Timestamp(1))
        _    <- KeyValueStore.put(ns, k1, v2, Timestamp(2))
        _    <- KeyValueStore.put(ns, k2, v1, Timestamp(1))
        keys <- KeyValueStore.scanAllKeys(ns).runCollect
        kvs  <- KeyValueStore.scanAll(ns).runCollect
      yield assertTrue(keys.toSet == Set(k1, k2)) && assertTrue(kvs.toMap == Map(k1 -> v2, k2 -> v1))
    }.provideLayer(KeyValueStore.inMemory),
    test("delete with marker keeps last not-older-than marker") {
      val ns = "del"
      val k  = Chunk.fromArray("k".getBytes)
      val v  = Chunk.fromArray("v".getBytes)
      for
        _   <- KeyValueStore.put(ns, k, v, Timestamp(1))
        _   <- KeyValueStore.put(ns, k, v, Timestamp(2))
        _   <- KeyValueStore.put(ns, k, v, Timestamp(3))
        _   <- KeyValueStore.delete(ns, k, Some(Timestamp(2)))
        got <- KeyValueStore.getLatest(ns, k, Some(Timestamp(2)))
      yield assertTrue(got.isDefined)
    }.provideLayer(KeyValueStore.inMemory),
  )
