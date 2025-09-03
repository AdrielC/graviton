package graviton

final case class InsertChunkResult(
    id: BinaryId,
    size: Long,
    deduplicated: Boolean
)
