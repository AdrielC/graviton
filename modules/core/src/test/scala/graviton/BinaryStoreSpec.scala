package graviton

import zio.*
import zio.stream.*
import zio.test.*
import graviton.impl.*
import graviton.core.BinaryAttributes

object BinaryStoreSpec extends ZIOSpecDefault:

  def spec = suite("BinaryStoreSpec")(
    test("store and retrieve data") {
      for
        store <- InMemoryBinaryStore.make()
        id    <- ZStream.fromIterable("hello".getBytes).run(store.put(BinaryAttributes.empty, 1024))
        out   <- store.get(id).someOrFailException.flatMap(_.runCollect)
      yield assertTrue(new String(out.toArray) == "hello")
    },
    test("delete removes data") {
      for
        store  <- InMemoryBinaryStore.make()
        id     <- ZStream.fromIterable("data".getBytes).run(store.put(BinaryAttributes.empty, 1024))
        _      <- store.delete(id)
        exists <- store.exists(id)
      yield assertTrue(!exists)
    },
  )
