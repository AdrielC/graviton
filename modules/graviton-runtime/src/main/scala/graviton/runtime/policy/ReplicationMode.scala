package graviton.runtime.policy

enum ReplicationMode derives CanEqual:
  case SyncAll
  case SyncQuorum
  case AsyncReplicas
