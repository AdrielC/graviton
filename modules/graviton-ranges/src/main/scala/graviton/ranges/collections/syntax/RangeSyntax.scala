package graviton.ranges.collections.syntax

import graviton.ranges.collections.{Discrete, Range}

trait RangeSyntax:
  extension [A](a: A)
    def toIncl(to: A): Range[A]                    = Range(a, to)
    def toExcl(to: A)(using Discrete[A]): Range[A] =
      Range(a, summon[Discrete[A]].pred(to))
