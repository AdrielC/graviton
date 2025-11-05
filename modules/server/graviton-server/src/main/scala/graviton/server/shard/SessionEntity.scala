package graviton.server.shard

import graviton.core.ranges.RangeSet
import graviton.runtime.constraints.SpillHandle

final case class SessionEntity(ranges: RangeSet[Long], spill: Option[SpillHandle])
