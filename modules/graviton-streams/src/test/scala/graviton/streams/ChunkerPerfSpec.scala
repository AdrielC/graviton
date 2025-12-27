package graviton.streams

import zio.*
import zio.stream.*
import zio.test.*

/**
 * Microbenchmarks are inherently noisy and should not gate CI.
 *
 * Run locally with:
 *   ./sbt "streams/testOnly graviton.streams.ChunkerPerfSpec"
 */
object ChunkerPerfSpec extends ZIOSpecDefault:

  private def bytes(n: Int): Chunk[Byte] =
    Chunk.fromArray((0 until n).map(_.toByte).toArray)

  private def chunked(in: Chunk[Byte], sizes: Chunk[Int]): Chunk[Chunk[Byte]] =
    val out = ChunkBuilder.make[Chunk[Byte]]()
    var idx = 0
    var si  = 0
    while idx < in.length do
      val n = math.max(1, sizes(si % sizes.length))
      out += in.slice(idx, math.min(in.length, idx + n))
      idx += n
      si += 1
    out.result()

  private def time[A](label: String)(zio: ZIO[Any, Throwable, A]): ZIO[Any, Throwable, A] =
    for
      t0 <- Clock.nanoTime
      a  <- zio
      t1 <- Clock.nanoTime
      ms  = (t1 - t0).toDouble / 1e6
      _  <- Console.printLine(f"$label%-28s $ms%8.2f ms")
    yield a

  override def spec: Spec[TestEnvironment, Any] =
    suite("ChunkerPerfSpec")(
      test("core step() vs ZPipeline (FastCDC)") {
        val input  = bytes(20_000_00) // 2MB
        val splits = Chunk(1, 7, 64, 3, 1024, 8192)
        val chunks = chunked(input, splits)

        val cfgMin = 256
        val cfgAvg = 1024
        val cfgMax = 4096

        val runCore =
          ZIO
            .fromEither(ChunkerCore.init(ChunkerCore.Mode.FastCdc(cfgMin, cfgAvg, cfgMax)))
            .mapError(err => new RuntimeException(err.toString))
            .flatMap { st0 =>
              ZIO.attempt {
                var st     = st0
                var blocks = 0

                var i = 0
                while i < chunks.length do
                  st.step(chunks(i)) match
                    case Left(err)            => throw new RuntimeException(err.toString)
                    case Right((st2, outBlk)) =>
                      st = st2
                      blocks += outBlk.length
                  i += 1

                st.finish match
                  case Left(err)          => throw new RuntimeException(err.toString)
                  case Right((_, outBlk)) => blocks += outBlk.length

                blocks
              }
            }

        val runPipeline =
          ZStream
            .fromChunks(chunks*)
            .via(Chunker.fastCdc(cfgMin, cfgAvg, cfgMax).pipeline)
            .runFold(0)((n, _) => n + 1)

        for
          _  <- Console.printLine("---- Chunker microbench (FastCDC) ----")
          b1 <- time("ChunkerCore (step loop)")(runCore)
          b2 <- time("ZStream.via(Chunker)")(runPipeline)
        yield assertTrue(b1 > 0, b2 > 0)
      } @@ TestAspect.ignore
    )
