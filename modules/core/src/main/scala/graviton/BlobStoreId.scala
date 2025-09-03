package graviton

final case class BlobStoreId(value: String) extends AnyVal

enum BlobStoreStatus:
  case Operational, ReadOnly, Retired
