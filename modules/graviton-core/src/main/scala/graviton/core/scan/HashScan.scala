package graviton.core.scan

import zio.Chunk
import java.security.MessageDigest

/**
 * Hashing scans using the FreeScan algebra.
 * 
 * Provides content-addressable digest computation with various emission strategies.
 */
object HashScan:
  
  /** State for incremental hashing */
  type HashState = Field["digest", MessageDigest] *: Field["count", Long] *: Ø
  
  /**
   * Emit a SHA-256 digest every N bytes, with final digest on flush.
   */
  def sha256Every(n: Int): FreeScan[Chunk, Chunk, Byte, Array[Byte], HashState] =
    val prim = new Scan[Chunk, Chunk, Byte, Array[Byte]]:
      type S = HashState
      
      val init = InitF.pure(
        rec.field("digest", MessageDigest.getInstance("SHA-256")) *:
        rec.field("count", 0L) *:
        EmptyTuple
      )
      
      val step = BiKleisli[Chunk, Chunk, Byte, (S, Array[Byte])] { bytes =>
        var md = MessageDigest.getInstance("SHA-256")
        var count = 0L
        var outputs = Chunk.empty[(S, Array[Byte])]
        
        bytes.foreach { b =>
          md.update(b)
          count += 1
          
          if (count % n == 0) {
            val hash = md.digest()
            val state: S = (
              rec.field("digest", MessageDigest.getInstance("SHA-256")) *:
              rec.field("count", count) *:
              EmptyTuple
            )
            outputs = outputs :+ (state, hash)
            md = MessageDigest.getInstance("SHA-256")
          }
        }
        
        // Update state even if no emission
        if (outputs.isEmpty) {
          val state: S = (
            rec.field("digest", md) *:
            rec.field("count", count) *:
            EmptyTuple
          )
          Chunk.single((state, Array.empty[Byte])).filter(_ => false).asInstanceOf[Chunk[(S, Array[Byte])]]
        } else {
          outputs
        }
      }
      
      def flush(finalS: S) =
        val md = get[S, "digest"](finalS, "digest")
        val count = get[S, "count"](finalS, "count")
        if (count % n != 0) {
          Chunk.single(Some(md.digest()))
        } else {
          Chunk.empty
        }
    
    FreeScan.fromScan(prim)
  
  /**
   * Single SHA-256 digest over entire input, emitted on flush.
   */
  def sha256: FreeScan[Chunk, Chunk, Byte, Array[Byte], HashState] =
    val prim = new Scan[Chunk, Chunk, Byte, Array[Byte]]:
      type S = HashState
      
      val init = InitF.pure(
        rec.field("digest", MessageDigest.getInstance("SHA-256")) *:
        rec.field("count", 0L) *:
        EmptyTuple
      )
      
      val step = BiKleisli[Chunk, Chunk, Byte, (S, Array[Byte])] { bytes =>
        val md = MessageDigest.getInstance("SHA-256")
        bytes.foreach(md.update)
        val state: S = (
          rec.field("digest", md) *:
          rec.field("count", bytes.length.toLong) *:
          EmptyTuple
        )
        Chunk.empty // No outputs during step
      }
      
      def flush(finalS: S) =
        val md = get[S, "digest"](finalS, "digest")
        Chunk.single(Some(md.digest()))
    
    FreeScan.fromScan(prim)
