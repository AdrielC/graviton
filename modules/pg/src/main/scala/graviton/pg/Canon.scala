package graviton.pg

import zio.Chunk
import zio.schema.DynamicValue

object Canon:
  def canonicalize(dv: DynamicValue): Chunk[Byte] =
    // Placeholder canonicalization
    Chunk.empty

  def storeKey(
      implId: String,
      dvCanon: Chunk[Byte],
      buildFp: Chunk[Byte],
      H: Chunk[Byte] => Chunk[Byte]
  ): StoreKey =
    val sep = Chunk.single(0.toByte)
    val material = Chunk.fromArray(
      implId.getBytes("UTF-8")
    ) ++ sep ++ dvCanon ++ sep ++ buildFp
    H(material).asInstanceOf[StoreKey]
