package graviton

import zio.*
import zio.stream.*
import zio.test.*
import graviton.impl.*

object BlockStoreSpec extends ZIOSpecDefault:

  def spec = suite("BlockStoreSpec")(
    test("put/get/has/delete") {
      for
        blob <- InMemoryBlobStore.make()
        store <- InMemoryBlockStore.make(blob)
        key <- ZStream
          .fromIterable("hello world".getBytes.toIndexedSeq)
          .run(store.put)
        has <- store.has(key)
        data <- store.get(key).someOrFailException.flatMap(_.runCollect)
        del <- store.delete(key)
      yield assertTrue(has && new String(data.toArray) == "hello world" && del)
    }
  )
