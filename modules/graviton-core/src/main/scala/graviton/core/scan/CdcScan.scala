package graviton.core.scan

import zio.Chunk

/**
 * Content-Defined Chunking scans using the FreeScan algebra.
 * 
 * Implements rolling-hash based boundary detection for deduplication.
 */
object CdcScan:
  
  /** State for CDC: rolling hash, buffer, boundaries */
  type CdcState = 
    Field["roll", Long] *: 
    Field["buffer", Chunk[Byte]] *: 
    Field["count", Long] *: 
    Ø
  
  /**
   * FastCDC-style boundary detection with min/avg/max constraints.
   * 
   * Emits chunk lengths when boundaries are detected.
   */
  def fastCdc(min: Int, avg: Int, max: Int): FreeScan[Chunk, Chunk, Byte, Int, CdcState] =
    require(min <= avg && avg <= max, s"Invalid CDC params: min=$min avg=$avg max=$max")
    
    // Simplified rolling hash mask (real impl would use FastCDC gear/mask computation)
    val mask = (1 << (log2(avg) - 1)) - 1
    
    val prim = new Scan[Chunk, Chunk, Byte, Int]:
      type S = CdcState
      
      val init = InitF.pure(
        rec.field("roll", 0L) *:
        rec.field("buffer", Chunk.empty[Byte]) *:
        rec.field("count", 0L) *:
        EmptyTuple
      )
      
      val step = BiKleisli[Chunk, Chunk, Byte, (S, Int)] { bytes =>
        var roll = 0L
        var buffer = Chunk.empty[Byte]
        var count = 0L
        var outputs = Chunk.empty[(S, Int)]
        
        bytes.foreach { b =>
          buffer = buffer :+ b
          count += 1
          
          // Update rolling hash
          roll = ((roll << 1) ^ (b & 0xFF)) & 0xFFFFFF
          
          // Check boundary conditions
          val isBoundary = 
            (count >= min && (roll & mask) == 0) || // Rolling hash match
            count >= max                             // Max size reached
          
          if (isBoundary) {
            val chunkLen = buffer.length
            val state: S = (
              rec.field("roll", 0L) *:
              rec.field("buffer", Chunk.empty[Byte]) *:
              rec.field("count", 0L) *:
              EmptyTuple
            )
            outputs = outputs :+ (state, chunkLen)
            
            // Reset for next chunk
            roll = 0L
            buffer = Chunk.empty
            count = 0L
          }
        }
        
        // Return state even if no boundary
        if (outputs.isEmpty) {
          val state: S = (
            rec.field("roll", roll) *:
            rec.field("buffer", buffer) *:
            rec.field("count", count) *:
            EmptyTuple
          )
          Chunk.single((state, 0)).filter(_ => false).asInstanceOf[Chunk[(S, Int)]]
        } else {
          // Update final state with remaining
          if (outputs.nonEmpty) {
            val lastState: S = (
              rec.field("roll", roll) *:
              rec.field("buffer", buffer) *:
              rec.field("count", count) *:
              EmptyTuple
            )
            outputs.init :+ (lastState, outputs.last._2)
          } else {
            outputs
          }
        }
      }
      
      def flush(finalS: S) =
        val buffer = get[S, "buffer"](finalS, "buffer")
        if (buffer.nonEmpty) {
          Chunk.single(Some(buffer.length))
        } else {
          Chunk.empty
        }
    
    FreeScan.fromScan(prim)
  
  /** Helper: log2 ceiling */
  private def log2(n: Int): Int =
    if (n <= 0) 0
    else 32 - Integer.numberOfLeadingZeros(n - 1)
  
  /**
   * Fixed-size chunking (degenerate case of CDC for testing).
   */
  def fixed(size: Int): FreeScan[Chunk, Chunk, Byte, Int, CdcState] =
    fastCdc(size, size, size)
