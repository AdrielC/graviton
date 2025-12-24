package graviton.runtime.legacy

final case class LegacyId(repo: String, docId: String)

final case class LegacyDescriptor(
  id: LegacyId,
  binaryHash: String,
  mime: String,
  length: Option[Long],
)
