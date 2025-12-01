package graviton

import zio.*
import zio.stream.*
import zio.test.*
import graviton.impl.*
import graviton.core.{BinaryAttributeKey, BinaryAttributes}

object FileStoreSpec extends ZIOSpecDefault:

  def spec = suite("CASSpec")(
    test("ingest and read back bytes via BlockStore") {
      for
        blob     <- InMemoryBlobStore.make
        resolver <- InMemoryBlockResolver.make
        blocks   <- InMemoryBlockStore.make(blob, resolver)
        key      <- ZStream
                      .fromIterable("abc" * 1000)
                      .map(_.toByte)
                      .run(blocks.put)
        out      <- blocks.get(key.head).someOrFailException.flatMap(_.runCollect)
      yield assertTrue(out.length == 3000)
    },
    test("invalid attributes are rejected by BinaryAttributes.validate") {
      val attrs =
        BinaryAttributes
          .advertised(BinaryAttributeKey.Client.fileName, "bad/name", "client") ++
          BinaryAttributes.advertised(BinaryAttributeKey.Client.contentType, "not/a^type", "client")
      BinaryAttributes.validate(attrs).exit.map(exit => assertTrue(exit.isFailure))
    },
  )
