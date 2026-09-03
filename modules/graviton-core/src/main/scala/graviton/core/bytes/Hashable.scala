package graviton.core.bytes

/** JVM-core compatibility alias for the portable byte-encoding typeclass. */
type Hashable[-A] = graviton.bytes.Hashable[A]

object Hashable:
  export graviton.bytes.Hashable.{apply, instance, given}

type HashInput = graviton.bytes.HashInput

object HashInput:
  export graviton.bytes.HashInput.{bytes, concat, empty, segments}

type HashSegment = graviton.bytes.HashSegment

object HashSegment:
  export graviton.bytes.HashSegment.{apply, bytes}
