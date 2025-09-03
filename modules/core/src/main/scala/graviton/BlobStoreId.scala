package graviton

opaque type BlobStoreId = String

object BlobStoreId:
  inline def apply(value: String): BlobStoreId = value

enum BlobStoreStatus:
  case Operational, ReadOnly, Retired
