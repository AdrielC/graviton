package graviton

import zio.*
import zio.stream.*
import zio.test.*
import zio.test.TestClock
import zio.durationInt
import graviton.impl.*
import graviton.ranges.ByteRange

object BlockStoreSpec extends ZIOSpecDefault:

  def spec = suite("BlockStoreSpec")(
    test("put/get/has/delete") {
      for
        blob     <- InMemoryBlobStore.make()
        resolver <- InMemoryBlockResolver.make
        store    <- InMemoryBlockStore.make(blob, resolver)
        key      <- ZStream
                      .fromIterable("hello world".getBytes.toIndexedSeq)
                      .run(store.put)
        has      <- store.has(key)
        data     <- store.get(key).someOrFailException.flatMap(_.runCollect)
        del      <- store.delete(key)
      yield assertTrue(has && new String(data.toArray) == "hello world" && del)
    },
    test("consult resolver across stores") {
      for
        primary   <- InMemoryBlobStore.make()
        secondary <- InMemoryBlobStore.make()
        resolver  <- InMemoryBlockResolver.make
        store     <- InMemoryBlockStore.make(primary, resolver, Seq(secondary))
        key       <- ZStream
                       .fromIterable("hello".getBytes.toIndexedSeq)
                       .run(store.put)
        _         <- secondary.write(key, Bytes(ZStream.fromIterable("hello".getBytes)))
        _         <- resolver.record(
                       key,
                       BlockSector(secondary.id, BlobStoreStatus.Operational),
                     )
        _         <- primary.delete(key)
        data      <- store.get(key).someOrFailException.flatMap(_.runCollect)
      yield assertTrue(new String(data.toArray) == "hello")
    },
    test("partial reads") {
      for
        blob     <- InMemoryBlobStore.make()
        resolver <- InMemoryBlockResolver.make
        store    <- InMemoryBlockStore.make(blob, resolver)
        key      <- ZStream
                      .fromIterable("hello world".getBytes.toIndexedSeq)
                      .run(store.put)
        part     <- store
                      .get(key, Some(ByteRange(6, 11)))
                      .someOrFailException
                      .flatMap(_.runCollect)
      yield assertTrue(new String(part.toArray) == "world")
    },
    test(
      "gc removes unreferenced blocks after retention and preserves live data"
    ) {
      for
        blob     <- InMemoryBlobStore.make()
        resolver <- InMemoryBlockResolver.make
        store    <- InMemoryBlockStore.make(blob, resolver)
        key      <- ZStream
                      .fromIterable("hello".getBytes.toIndexedSeq)
                      .run(store.put)
        _        <- ZStream
                      .fromIterable("hello".getBytes.toIndexedSeq)
                      .run(store.put)
        _        <- store.delete(key)
        _        <- TestClock.adjust(1.second)
        removed0 <- store.gc(GcConfig(1.second))
        still    <- store.has(key)
        _        <- store.delete(key)
        _        <- TestClock.adjust(1.second)
        removed  <- store.gc(GcConfig(1.second))
        gone     <- store.has(key)
      yield assertTrue(removed0 == 0 && still && removed == 1 && !gone)
    },
  )
