package graviton

opaque type AttributeName = String

object AttributeName:
  inline def apply(name: String): AttributeName = name
