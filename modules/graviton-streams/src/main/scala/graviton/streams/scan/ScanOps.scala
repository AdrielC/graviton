package graviton.streams.scan

import zio.stream.{ZPipeline, ZStream}

object ScanOps:

  /** Lift a [[Scan]] into a reusable [[ZPipeline]]. */
  def pipe[In, State, Out](scan: Scan[In, State, Out]): ZPipeline[Any, Nothing, In, Out] =
    scan.pipeline

  extension [R, E, In](stream: ZStream[R, E, In])
    /** Apply the supplied scan to this stream. */
    def throughScan[State, Out](scan: Scan[In, State, Out]): ZStream[R, E, Out] =
      scan.applyTo(stream)
