package graviton

import graviton.impl.*
import zio.*
import zio.stream.*
import zio.test.*

object BinaryStoreSpec extends ZIOSpecDefault:

  def spec = suite("BinaryStoreSpec")(
    test("store and retrieve data") {
      for
        store <- InMemoryBinaryStore.make()
        id    <- ZStream.fromIterable("hello".getBytes).run(store.put)
        out   <- store.get(id).someOrFailException.flatMap(_.runCollect)
      yield assertTrue(new String(out.toArray) == "hello")
    },
    test("delete removes data") {
      for
        store  <- InMemoryBinaryStore.make()
        id     <- ZStream.fromIterable("data".getBytes).run(store.put)
        _      <- store.delete(id)
        exists <- store.exists(id)
      yield assertTrue(!exists)
    },
  )
