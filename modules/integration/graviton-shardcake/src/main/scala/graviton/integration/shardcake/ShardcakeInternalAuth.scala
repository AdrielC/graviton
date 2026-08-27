package graviton.integration.shardcake

private[shardcake] object ShardcakeInternalAuth:
  val HeaderName = "x-graviton-shardcake-token"

  def matches(provided: String, expected: ShardcakeInternalToken): Boolean =
    if provided == null then false
    else
      val right   = expected.value
      val maximum = math.max(provided.length, right.length)
      var diff    = provided.length ^ right.length
      var index   = 0
      while index < maximum do
        val leftChar  = if index < provided.length then provided.charAt(index).toInt else 0
        val rightChar = if index < right.length then right.charAt(index).toInt else 0
        diff = diff | (leftChar ^ rightChar)
        index += 1
      diff == 0
