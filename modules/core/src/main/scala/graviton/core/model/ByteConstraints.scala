package graviton.core.model



object ByteConstraints:


  import io.github.iltotore.iron.constraint.any.DescribedAs
  import io.github.iltotore.iron.{zio as _, *}


  object GlobalLimits:

    inline final val MIN_BLOCK_SIZE_IN_BYTES = 1
    final type MIN_BLOCK_SIZE_IN_BYTES = MIN_BLOCK_SIZE_IN_BYTES.type

    inline final val MIN_UPLOAD_CHUNK_SIZE_IN_BYTES = 1
    final type MIN_UPLOAD_CHUNK_SIZE_IN_BYTES = MIN_UPLOAD_CHUNK_SIZE_IN_BYTES.type

    inline final val MAX_UPLOAD_CHUNK_SIZE_IN_BYTES = 1048576 // 1MB
    final type MAX_UPLOAD_CHUNK_SIZE_IN_BYTES = MAX_UPLOAD_CHUNK_SIZE_IN_BYTES.type

    inline final val MAX_BLOCK_SIZE_IN_BYTES = 512 * 1024 * 1024 // 512MB
    final type MAX_BLOCK_SIZE_IN_BYTES = MAX_BLOCK_SIZE_IN_BYTES.type

    inline final val MAX_FILE_SIZE_IN_BYTES = 1L * 1024 * 1024 * 1024 * 1024 // 1TB
    final type MAX_FILE_SIZE_IN_BYTES = MAX_FILE_SIZE_IN_BYTES.type

    inline final val MIN_FILE_SIZE_IN_BYTES = 0L
    final type MIN_FILE_SIZE_IN_BYTES = MIN_FILE_SIZE_IN_BYTES.type

  end GlobalLimits

  export GlobalLimits.*



  import scala.compiletime.ops.int
  import scala.compiletime.ops.long
  import scala.compiletime.ops.long.ToInt
  import scala.compiletime.ops.int.ToLong
  infix type T[C[_, _]] = [a, b] =>> C[a, b] =:= true & Boolean match
      case int.`<`[? <: Int, ? <: Int] => int.`<`[a, b] =:= true
      case int.`<=`[? <: Int, ? <: Int] => int.`<=`[a, b] =:= true
      case int.`>`[? <: Int, ? <: Int] => int.`>`[a, b] =:= true
      case int.`>=`[? <: Int, ? <: Int] => int.`>=`[a, b] =:= true
      case long.`<`[? <: Long, ? <: Long] => long.`<`[a, b] =:= true
      case long.`<=`[? <: Long, ? <: Long] => long.`<=`[a, b] =:= true
      case long.`>`[? <: Long, ? <: Long] => long.`>`[a, b] =:= true
      case long.`>=`[? <: Long, ? <: Long] => long.`>=`[a, b] =:= true
  end T

  infix type <<=[A <: Int | Long | Sized[?, ?, ?], B <: Int | Long | Sized[?, ?, ?]] <: =:=[? <: Boolean, true] = 
    A match 
      case Int => B match
        case Int => int.`<=`[A, B] =:= true
        case Long => int.`<=`[A, ToLong[B]] =:= true
      case Long => B match
        case Int => long.`<=`[A, ToInt[B]] =:= true
        case Long => long.`<=`[A, B] =:= true
      
  infix type >>=[A <: Int | Long | Sized[?, ?, ?], B <: Int | Long | Sized[?, ?, ?]] <: =:=[? <: Boolean, true] = 
    A match 
      case Int => B match
        case Int => int.`>=`[A, B] =:= true
        case Long => int.`>=`[A, ToLong[B]] =:= true
      case Long => B match
        case Int => long.`>=`[A, ToInt[B]] =:= true
        case Long => long.`>=`[A, B] =:= true 
  
  
  
  // transparent trait MinMax[Min <: Int | Long | Sized[? <: Int | Long, ? <: Min, ? <: Int | Long], Max <: Min | Sized[? <: Int | Long, ? <: Min, ? <: Int | Long]](
  //   using Min <<= Max, Min >>= ZeroOf[Min], Max <<= MaxOf[Max], Max >>= Min,
  // )

  // object MinMax:
  //   inline given [Min <: Int | Long | Sized[? <: Int | Long, ? <: Min, ? <: Int | Long], Max <: Min] => (
  //     Min <<= Max, Min >>= ZeroOf[Min], Max >>= Min,
  //   ) => MinMax[Min, Max] = 
  //     val (a, b, c, d) = allConstraints[Min, Max]
  //     new MinMax[Min, Max](using a, b, c, d) {}
  // end MinMax

  type InlMinMax[Min <: Int | Long | Sized[? <: Int | Long, ? <: Min, ? <: Int | Long], Max <: Min | Sized[? <: Int | Long, ? <: Min, ? <: Max]] = 
    ((Min <<= Max), (Min >>= ZeroOf[Min]), (Max <<= MaxOf[Max]), (Max >>= Min))
  
  inline def allConstraints[Min <: Int | Long | Sized[? <: Int | Long, ? <: Min, ? <: Int | Long], Max <: Min | Sized[? <: Int | Long, ? <: Min, ? <: Max]]
  : InlMinMax[Min, Max] = 
    compiletime.summonAll[InlMinMax[Min, Max]]  


  sealed transparent trait ByteSizeBounds[N <: Int | Long, Min <: N, Max <: N]
    extends ByteSize[N, Min, Max]:
      self: ByteSize[N, Min, Max] =>

  end ByteSizeBounds


  type UploadChunkSize = UploadChunkSize.T
  object UploadChunkSize extends ByteSizeBounds[Int, GlobalLimits.MIN_UPLOAD_CHUNK_SIZE_IN_BYTES, GlobalLimits.MAX_UPLOAD_CHUNK_SIZE_IN_BYTES]

  type BlockSize = BlockSize.T
  object BlockSize extends ByteSizeBounds[Int, GlobalLimits.MIN_BLOCK_SIZE_IN_BYTES, GlobalLimits.MAX_BLOCK_SIZE_IN_BYTES]


  type FileSize = FileSize.T
  object FileSize extends ByteSizeBounds[Long, GlobalLimits.MIN_FILE_SIZE_IN_BYTES, GlobalLimits.MAX_FILE_SIZE_IN_BYTES]

end ByteConstraints

export ByteConstraints.{MAX_BLOCK_SIZE_IN_BYTES, MAX_FILE_SIZE_IN_BYTES}
