package graviton.streams

import zio.Chunk
import zio.stream.{ZSink, ZPipeline}

type ChunkingSink[A] = ZSink[Any, Nothing, A, A, Chunk[A]]

object ChunkingSink:  
  
  // default to 516k chunks

  def fixed(limit: Int = 516 * 1024): ForAny[ChunkingSink] = 
    ForAny.ChunkingSink([?+] => () => ZSink.collectAllN(limit))

end ChunkingSink

trait ForAny[F[_]]:

	def apply[A]: F[A]

end ForAny

object ForAny:

  def apply[F[_]]: ([?+] => (() => F[?+])) => ForAny[F] = f => new: 
    def apply[A]: F[A] = f()

  val ChunkingSink = ForAny[ChunkingSink]

end ForAny

object StreamTools:

  def teeTo[R, E, A](
    sink: ZSink[R, E, A, A, Any],
    rechunker: ChunkingSink[A] = ChunkingSink.fixed()[A]
  ): ZPipeline[R, E, A, A] =
    ZPipeline.fromFunction(_.transduce(sink zipParRight rechunker).flattenChunks)

end StreamTools