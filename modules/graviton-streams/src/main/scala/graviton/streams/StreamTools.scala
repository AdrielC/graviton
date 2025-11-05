package graviton.streams

import zio.stream.{ZSink, ZStream}

object StreamTools:
  def teeTo[R, E, A](stream: ZStream[R, E, A], sink: ZSink[R, E, A, Any, Any]): ZStream[R, E, A] =
    stream.tap(value => ZStream.succeed(value).run(sink).unit)
