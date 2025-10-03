package graviton.runtime.policy

final case class StorePolicy(layout: BlobLayout, minPartSize: Long, replication: ReplicationMode)
