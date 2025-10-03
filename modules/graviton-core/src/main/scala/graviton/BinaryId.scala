package graviton

opaque type BinaryId = String

object BinaryId:
  inline def apply(value: String): BinaryId = value
