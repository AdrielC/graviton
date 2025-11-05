package graviton.runtime.policy

enum BlobLayout derives CanEqual:
  case Monolithic
  case FramedManifestChunks
