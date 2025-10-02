package graviton.ranges

import zio.json.*

object RangeSetJson:
  given [A: JsonEncoder: JsonDecoder](using domain: DiscreteDomain[A]): JsonCodec[RangeSet[A]] =
    summon[JsonCodec[List[(A, A)]]].transform(
      pairs => RangeSet.fromSpans(pairs.map(Span.apply)),
      rangeSet => rangeSet.intervals.map(span => (span.start, span.endExclusive)),
    )
