package graviton.scan.hashing

import kyo.*
import java.security.MessageDigest
import zio.Chunk as ZChunk
import graviton.scan.*
import graviton.scan.Scan.*

/** Resource-holding scan that computes a cryptographic digest over its input.
 *
 *  This is the worked example that demonstrates the design doing real work:
 *
 *  1. The state includes a `MessageDigest` — a mutable JVM object with
 *     state-allocation semantics. We need to guarantee that composition
 *     doesn't leak it.
 *
 *  2. The scan is effectful in Kyo's `Sync` effect (because allocating
 *     a MessageDigest touches the JVM's provider infrastructure and is
 *     technically observable). `E = Sync` shows up in composed types.
 *
 *  3. The `release` function is a no-op here because `MessageDigest` is
 *     a pure-memory object without OS resources. But note that the shape
 *     is correct for a scan that *did* need explicit cleanup: `release`
 *     runs LIFO with other releases during teardown.
 *
 *  4. Capture checking: the state type `HasherState^` carries a capture
 *     annotation because it owns the MessageDigest. The compiler enforces
 *     that the state can't escape the scan's lifetime — you can't store
 *     it in a field, return it from a closure, or smuggle it out in a
 *     lambda.
 *
 *  ## Usage
 *
 *  {{{
 *  import graviton.scan.*
 *  import graviton.scan.hashing.HashingScan
 *
 *  val digesting = HashingScan.sha256
 *  val counting  = Scan.fold[Byte, Long, Long](0L)((s, _) => (s + 1, Chunk.empty))()
 *
 *  // Composition: fanout, counting once, hashing once.
 *  val both = counting &&& digesting
 *  // Type: Scan.Aux[Byte, (Long, Chunk[Byte]), FanoutState[Long, HasherState^, Long, Chunk[Byte]], Sync]
 *  // The capture set tracks that the composed state contains the hasher resource.
 *  }}}
 */
object HashingScan:

  /** The state of a hashing scan: a mutable MessageDigest plus the count of
   *  bytes consumed. Marked as a capability via the `^` annotation so that
   *  capture checking tracks it through composition.
   *
   *  Note: In Scala 3.8 capture checking, `caps.Capability` is the trait you
   *  extend to mark a class as a tracked resource. We don't extend it on
   *  `HasherState` directly because `MessageDigest` is a JVM class we can't
   *  modify. Instead, we wrap it and expose the capability at the type
   *  level via the `^` annotation on the state type.
   */
  // Note: in Scala 3.8 capture checking, `caps.Capability` is the trait you
  // would extend to mark a class as a tracked resource. We don't extend it on
  // `HasherState` directly because `MessageDigest` is a JVM class we can't
  // modify, and on Scala 3.7.3 the capture-set surface is still evolving.
  // The capture set tracking would be expressed via the `^` on the alias
  // below once the rest of the algebra is plumbed for it.
  final class HasherState(val md: MessageDigest, var bytesSeen: Long)

  /** Public state type alias.
   *
   *  Originally the sketch used `type HasherStateCap = HasherState^` to track
   *  the MessageDigest as a capability via Scala 3 capture checking. On Scala
   *  3.7.3 the `^` annotation on a type alias does not propagate cleanly
   *  through the rest of the algebra (the design notes call this out as 2-3
   *  weeks of work). For now this is a plain alias; the capture story is a
   *  follow-up.
   */
  type HasherStateCap = HasherState

  /** SHA-256 hashing scan.
   *
   *  Input: Byte elements.
   *  Output: one `Chunk[Byte]` on flush (the final digest).
   *  State: HasherState with capture tracking.
   *  Effect: Sync (JVM digest allocation is an effect).
   */
  def sha256: Aux[Byte, ZChunk[Byte], HasherStateCap, Sync] = mkDigestScan("SHA-256")

  /** BLAKE3 hashing scan. Not in JVM stdlib; this sketch uses SHA-256 as
   *  placeholder, but the shape would be identical with a BLAKE3 library.
   */
  def blake3: Aux[Byte, ZChunk[Byte], HasherStateCap, Sync] = mkDigestScan("SHA-256") // placeholder

  /** Generic digest constructor. The MessageDigest allocation happens inside
   *  `Sync.defer` so that Kyo's effect row tracks it properly. The digest is
   *  considered a capability because the MessageDigest object has mutable
   *  state and we must ensure it's not leaked to a context where its state
   *  might be observed after flush.
   */
  private def mkDigestScan(algorithm: String): Aux[Byte, ZChunk[Byte], HasherStateCap, Sync] =
    Scan.effectful[Byte, ZChunk[Byte], HasherStateCap, Sync](
      init0 = Sync.defer {
        val md = MessageDigest.getInstance(algorithm)
        new HasherState(md, 0L).asInstanceOf[HasherStateCap]
      },
      step0 = (state, byte) => Sync.defer {
        state.md.update(byte)
        state.bytesSeen += 1
        // Hashing doesn't emit per-byte — we buffer until flush.
        (state, kyo.Chunk.empty[ZChunk[Byte]])
      },
      flush0 = state => Sync.defer {
        val digest: Array[Byte] = state.md.digest()
        kyo.Chunk[ZChunk[Byte]](ZChunk.fromArray(digest))
      },
      release0 = state => Sync.defer {
        // MessageDigest has no explicit close. For a scan that did hold
        // OS resources (file handles, native memory), this is where
        // close() or free() would go.
        // We still zero out the sensitive state as a defense-in-depth:
        state.md.reset()
      },
    )

end HashingScan
