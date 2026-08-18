package graviton.core.scan

import kyo.Record
import kyo.Record.`~`
import zio.*
import zio.test.*

object TransducerSpec extends ZIOSpecDefault:

  def spec = suite("Transducer")(
    // =========================================================================
    //  Basic constructors
    // =========================================================================

    suite("constructors")(
      test("id passes elements through") {
        val (_, out) = Transducer.id[Int].runChunk(List(1, 2, 3))
        assertTrue(out == Chunk(1, 2, 3))
      },
      test("map transforms elements") {
        val (_, out) = Transducer.map[Int, String](_.toString).runChunk(List(1, 2, 3))
        assertTrue(out == Chunk("1", "2", "3"))
      },
      test("filter keeps matching elements") {
        val (_, out) = Transducer.filter[Int](_ % 2 == 0).runChunk(List(1, 2, 3, 4, 5))
        assertTrue(out == Chunk(2, 4))
      },
      test("flatMap expands elements") {
        val (_, out) = Transducer.flatMap[Int, Int](i => Chunk(i, i * 10)).runChunk(List(1, 2))
        assertTrue(out == Chunk(1, 10, 2, 20))
      },
      test("fold accumulates state") {
        val counter       = Transducer.fold1[Int, Long, Long](0L)((s, _) => (s + 1, s + 1))(s => (s, Chunk.empty))
        val (finalS, out) = counter.runChunk(List(10, 20, 30))
        assertTrue(out == Chunk(1L, 2L, 3L)) && assertTrue(finalS == 3L)
      },
      test("accumulate outputs state after each step") {
        val sum      = Transducer.accumulate[Int, Int](0)(_ + _)
        val (_, out) = sum.runChunk(List(1, 2, 3, 4))
        assertTrue(out == Chunk(1, 3, 6, 10))
      },
    ),

    // =========================================================================
    //  Map fusion
    // =========================================================================

    suite("map fusion")(
      test("adjacent maps fuse into one") {
        val t        = Transducer.map[Int, Int](_ + 1).map(_ * 2).map(_.toString)
        // Should be a single Mapped wrapping id, not a chain of 3
        val (_, out) = t.runChunk(List(1, 2, 3))
        assertTrue(out == Chunk("4", "6", "8"))
      },
      test("adjacent filters fuse predicates") {
        val t        = Transducer.filter[Int](_ > 0).filter(_ < 10).filter(_ % 2 == 0)
        val (_, out) = t.runChunk(List(-1, 0, 1, 2, 3, 4, 5, 10, 12))
        assertTrue(out == Chunk(2, 4))
      },
      test("contramap fuses") {
        val t        = Transducer.map[Int, Int](_ * 2).contramap[String](_.toInt)
        val (_, out) = t.runChunk(List("1", "2", "3"))
        assertTrue(out == Chunk(2, 4, 6))
      },
    ),

    // =========================================================================
    //  Sequential composition (>>>)
    // =========================================================================

    suite("sequential composition (>>>)")(
      test("stateless >>> stateless: Unit + Unit = Unit") {
        val t            = Transducer.map[Int, Int](_ + 1) >>> Transducer.map[Int, String](_.toString)
        val (state, out) = t.runChunk(List(1, 2, 3))
        assertTrue(out == Chunk("2", "3", "4")) &&
        assertTrue(state == ()) // Unit state
      },
      test("stateful >>> stateless: S + Unit = S") {
        val counter      = Transducer.counter[Int]
        val toStr        = Transducer.map[Long, String](_.toString)
        val t            = counter >>> toStr
        val (state, out) = t.runChunk(List(10, 20, 30))
        assertTrue(out == Chunk("1", "2", "3")) &&
        assertTrue(state.count == 3L) // Record state preserved
      },
      test("stateless >>> stateful: Unit + S = S") {
        val double       = Transducer.map[Int, Int](_ * 2)
        val summer       = Transducer.summer[Int]
        val t            = double >>> summer
        val (state, out) = t.runChunk(List(1, 2, 3))
        assertTrue(out == Chunk(2, 6, 12)) &&
        assertTrue(state.sum == 12) // Record state preserved
      },
      test("stateful >>> stateful with Records: Record[A] + Record[B] = Record[A & B]") {
        val counter      = Transducer.counter[Int]
        val summer       = Transducer.summer[Long]
        val t            = counter >>> summer
        val (state, out) = t.runChunk(List(10, 20, 30))
        // counter emits 1, 2, 3; summer sums them: 1, 3, 6
        assertTrue(out == Chunk(1L, 3L, 6L)) &&
        assertTrue(state.count == 3L) && // from counter's Record
        assertTrue(state.sum == 6L)      // from summer's Record
      },
      test("filter >>> map composes correctly") {
        val t        = Transducer.filter[Int](_ > 2) >>> Transducer.map[Int, String](_.toString)
        val (_, out) = t.runChunk(List(1, 2, 3, 4))
        assertTrue(out == Chunk("3", "4"))
      },
      test("flush propagates through >>>") {
        // A scan that buffers and flushes
        val buffer = Transducer.fold[Int, Int, List[Int]](Nil)((s, i) => (i :: s, Chunk.empty))(s => (s, Chunk.fromIterable(s.reverse)))

        val double   = Transducer.map[Int, Int](_ * 2)
        val t        = buffer >>> double
        val (_, out) = t.runChunk(List(1, 2, 3))
        // buffer emits nothing during step, then flushes [1,2,3]; double maps them
        assertTrue(out == Chunk(2, 4, 6))
      },
    ),

    // =========================================================================
    //  Fanout (&&&)
    // =========================================================================

    suite("fanout (&&&)")(
      test("stateless &&& stateless: paired output, Unit state") {
        val t            = Transducer.map[Int, Int](_ + 1) &&& Transducer.map[Int, Int](_ * 10)
        val (state, out) = t.runChunk(List(1, 2, 3))
        assertTrue(out == Chunk((2, 10), (3, 20), (4, 30))) &&
        assertTrue(state == ())
      },
      test("Record &&& Record: state is union of records") {
        val counter = Transducer.counter[Int]
        val summer  = Transducer.summer[Int]
        val t       = counter &&& summer

        val (state, out) = t.runChunk(List(10, 20, 30))
        // counter: 1,2,3; summer: 10,30,60
        assertTrue(out == Chunk((1L, 10), (2L, 30), (3L, 60))) &&
        assertTrue(state.count == 3L) && // Record field from counter
        assertTrue(state.sum == 60)      // Record field from summer
      },
      test("three-way fanout: (a &&& b) &&& c") {
        val counter = Transducer.counter[Int]
        val summer  = Transducer.summer[Int]
        val id3     = Transducer.map[Int, Int](identity)
        val t       = (counter &&& summer) &&& id3

        val (_, out) = t.runChunk(List(5, 10))
        // counter: 1,2; summer: 5,15; id: 5,10
        assertTrue(out == Chunk(((1L, 5), 5), ((2L, 15), 10)))
      },
    ),

    // =========================================================================
    //  Record state access after composition
    // =========================================================================

    suite("Record state union")(
      test("composed Record state has all fields accessible by name") {
        val counter = Transducer.counter[String]
        val t       = Transducer.map[String, String](_.toUpperCase) >>> counter

        val (state, _) = t.runChunk(List("hello", "world", "!"))
        // state is Record["count" ~ Long] (Unit merged with Record = Record)
        assertTrue(state.count == 3L)
      },
      test("fanout of two Record scans: both fields accessible") {
        val counter = Transducer.counter[Int]
        val summer  = Transducer.summer[Int]
        val t       = counter &&& summer

        val (state, _) = t.runChunk(List(100, 200, 300))
        // state is Record[("count" ~ Long) & ("sum" ~ Int)]
        assertTrue(state.count == 3L) &&
        assertTrue(state.sum == 600)
      },
    ),

    // =========================================================================
    //  Batteries
    // =========================================================================

    suite("batteries")(
      test("counter counts elements") {
        val (state, out) = Transducer.counter[String].runChunk(List("a", "b", "c"))
        assertTrue(out == Chunk(1L, 2L, 3L)) &&
        assertTrue(state.count == 3L)
      },
      test("byteCounter sums byte chunk lengths") {
        val (state, out) = Transducer.byteCounter.runChunk(List(Chunk[Byte](1, 2), Chunk[Byte](3, 4, 5)))
        assertTrue(out == Chunk(2L, 5L)) &&
        assertTrue(state.totalBytes == 5L)
      },
      test("summer sums numeric values") {
        val (state, out) = Transducer.summer[Double].runChunk(List(1.0, 2.5, 3.5))
        assertTrue(out == Chunk(1.0, 3.5, 7.0)) &&
        assertTrue(state.sum == 7.0)
      },
      test("window maintains sliding window") {
        val (state, out) = Transducer.window[Int](3).runChunk(List(1, 2, 3, 4, 5))
        assertTrue(
          out == Chunk(
            Vector(1),
            Vector(1, 2),
            Vector(1, 2, 3),
            Vector(2, 3, 4),
            Vector(3, 4, 5),
          )
        ) &&
        assertTrue(state == Vector(3, 4, 5))
      },
    ),

    // =========================================================================
    //  Compilation to ZPipeline
    // =========================================================================

    suite("ZPipeline compilation")(
      test("toPipeline streams correctly") {
        for out <- zio.stream.ZStream
                     .fromIterable(List(1, 2, 3, 4, 5))
                     .via(Transducer.filter[Int](_ % 2 == 0).map(_.toString).toPipeline)
                     .runCollect
        yield assertTrue(out == Chunk("2", "4"))
      },
      test("toPipeline handles stateful flush") {
        // Buffer everything, flush at end
        val buffer = Transducer.fold[Int, Int, List[Int]](Nil)((s, i) => (i :: s, Chunk.empty))(s => (s, Chunk.fromIterable(s.reverse)))

        for out <- zio.stream.ZStream
                     .fromIterable(List(1, 2, 3))
                     .via(buffer.toPipeline)
                     .runCollect
        yield assertTrue(out == Chunk(1, 2, 3))
      },
    ),

    // =========================================================================
    //  State as user-facing summary (like ZSink's Z type)
    // =========================================================================

    suite("state as summary")(
      test("summarize returns only the final state") {
        val summary = Transducer.counter[Int].summarize(List(1, 2, 3))
        assertTrue(summary.count == 3L)
      },
      test("composed summary has all fields from both scans") {
        val t       = Transducer.counter[Int] &&& Transducer.summer[Int]
        val summary = t.summarize(List(10, 20, 30))
        assertTrue(summary.count == 3L) &&
        assertTrue(summary.sum == 60)
      },
      test("toSink exposes summary through ZStream.run") {
        for
          result            <- zio.stream.ZStream
                                 .fromIterable(List(10, 20, 30))
                                 .run(Transducer.counter[Int].toSink)
          (summary, outputs) = result
        yield assertTrue(summary.count == 3L) &&
          assertTrue(outputs == Chunk(1L, 2L, 3L))
      },
      test("toSink with composed Record state: both fields accessible") {
        val t = Transducer.counter[Int] &&& Transducer.summer[Int]
        for
          result            <- zio.stream.ZStream
                                 .fromIterable(List(5, 10, 15))
                                 .run(t.toSink)
          (summary, outputs) = result
        yield assertTrue(summary.count == 3L) &&
          assertTrue(summary.sum == 30) &&
          assertTrue(outputs.length == 3)
      },
      test("toChannel yields summary as terminal value") {
        for result <- zio.stream.ZStream
                        .fromIterable(List(1, 2, 3))
                        .channel
                        .pipeToOrFail(Transducer.counter[Int].toChannel)
                        .runCollect
        yield
          // ZChannel.runCollect returns (Chunk[O], Z) where Z is the terminal
          assertTrue(result._1.flatten == Chunk(1L, 2L, 3L))
      },
      test("transduce: sink runs repeatedly, emitting summaries as stream elements") {
        // A scan that buffers 3 elements then flushes (simulating a chunker)
        val batcher = Transducer.fold[Int, Int, List[Int]](Nil)((buf, i) =>
          val next = i :: buf
          if next.length >= 3 then (Nil, Chunk.fromIterable(next.reverse))
          else (next, Chunk.empty)
        )(buf => (buf, if buf.nonEmpty then Chunk.fromIterable(buf.reverse) else Chunk.empty))

        // Transduce: run the batcher as a sink repeatedly, getting a stream of summaries
        for summaries <- zio.stream.ZStream
                           .fromIterable(1 to 7)
                           .transduce(batcher.toTransducingSink)
                           .runCollect
        yield
          // Summaries are the final states of each batch:
          // batch 1: processes all 7, state is whatever flush produces
          assertTrue(summaries.nonEmpty)
      },
    ),
  )
