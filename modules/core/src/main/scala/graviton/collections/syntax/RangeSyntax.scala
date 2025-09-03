package graviton.collections.syntax

import graviton.collections.{Range, Discrete}

trait RangeSyntax:
  extension [A](a: A)
    def toIncl(to: A): Range[A] = Range(a, to)
    def toExcl(to: A)(using Discrete[A]): Range[A] =
      Range(a, summon[Discrete[A]].pred(to))
