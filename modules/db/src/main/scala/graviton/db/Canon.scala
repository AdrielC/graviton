package graviton.db

import zio.Chunk
import zio.schema.DynamicValue
import zio.schema.codec.JsonCodec
import zio.schema.codec.JsonCodec.ExplicitConfig

object Canon:
  private val codec = JsonCodec.schemaBasedBinaryCodec[DynamicValue](
    JsonCodec.Configuration(
      explicitNulls = ExplicitConfig(encoding = false),
      explicitEmptyCollections = ExplicitConfig(encoding = false),
    )
  )

  def canonicalize(dv: DynamicValue): Chunk[Byte] =
    codec.encode(dv)

  def storeKey(
    implId: String,
    dvCanon: Chunk[Byte],
    buildFp: Chunk[Byte],
    H: Chunk[Byte] => Chunk[Byte],
  ): StoreKey =
    val sep      = Chunk.single(0.toByte)
    val material = Chunk.fromArray(
      implId.getBytes("UTF-8")
    ) ++ sep ++ dvCanon ++ sep ++ buildFp

    StoreKey.applyUnsafe(H(material))
