package graviton.server.shard

object ShardKeys:
  def forUpload(uploadId: String): String = s"upload-$uploadId"
