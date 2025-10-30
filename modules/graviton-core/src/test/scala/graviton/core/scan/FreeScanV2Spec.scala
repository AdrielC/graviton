package graviton.core.scan

import zio.test.*
import zio.Chunk

/**
 * Tests for simplified FreeScanV2.
 */
object FreeScanV2Spec extends ZIOSpecDefault:

  def spec = suite("FreeScan V2 - Simplified")(
    test("Id scan passes through") {
      val scan    = Scan.id[Int]
      var state   = scan.init
      val outputs = Chunk.newBuilder[Int]

      List(1, 2, 3).foreach { i =>
        val (s2, chunk) = scan.step(state, i)
        state = s2
        outputs ++= chunk
      }
      outputs ++= scan.flush(state)

      assertTrue(outputs.result() == Chunk(1, 2, 3))
    },
    test("arr lifts pure function") {
      val scan    = Scan.arr[Int, Int](_ * 2)
      var state   = scan.init
      val outputs = Chunk.newBuilder[Int]

      List(1, 2, 3).foreach { i =>
        val (s2, chunk) = scan.step(state, i)
        state = s2
        outputs ++= chunk
      }
      outputs ++= scan.flush(state)

      assertTrue(outputs.result() == Chunk(2, 4, 6))
    },
    test("fold accumulates state") {
      val scan    = Scan.fold(0L)((s: Long, _: Byte) => s + 1)
      var state   = scan.init
      val outputs = Chunk.newBuilder[Long]

      List[Byte](1, 2, 3).foreach { b =>
        val (s2, chunk) = scan.step(state, b)
        state = s2
        outputs ++= chunk
      }
      outputs ++= scan.flush(state)

      assertTrue(
        state == 3L &&
          outputs.result() == Chunk(1L, 2L, 3L)
      )
    },
    test("Sequential composition (>>>)") {
      val doubler  = Scan.arr[Int, Int](_ * 2)
      val summer   = Scan.fold(0)((s: Int, i: Int) => s + i)
      val pipeline = doubler >>> summer

      var state   = pipeline.init
      val outputs = Chunk.newBuilder[Int]

      List(1, 2, 3).foreach { i =>
        val (s2, chunk) = pipeline.step(state, i)
        state = s2
        outputs ++= chunk
      }
      outputs ++= pipeline.flush(state)

      val (_, sumState) = state
      assertTrue(
        sumState == 12 && // 2 + 4 + 6
          outputs.result() == Chunk(2, 6, 12)
      )
    },
    test("Fanout (&&&)") {
      val counter = Scan.fold(0L)((s: Long, _: Byte) => s + 1)
      val summer  = Scan.fold(0L)((s: Long, b: Byte) => s + b)
      val fanout  = counter &&& summer

      var state   = fanout.init
      val outputs = Chunk.newBuilder[(Long, Long)]

      List[Byte](1, 2, 3).foreach { b =>
        val (s2, chunk) = fanout.step(state, b)
        state = s2
        outputs ++= chunk
      }
      outputs ++= fanout.flush(state)

      val (countState, sumState) = state
      assertTrue(
        countState == 3L &&
          sumState == 6L &&
          outputs.result() == Chunk((1L, 1L), (2L, 3L), (3L, 6L))
      )
    },
    test("map transforms outputs") {
      val counter = Scan.fold(0L)((s: Long, _: Byte) => s + 1)
      val mapped  = counter.map(_ * 10)

      var state   = mapped.init
      val outputs = Chunk.newBuilder[Long]

      List[Byte](1, 2, 3).foreach { b =>
        val (s2, chunk) = mapped.step(state, b)
        state = s2
        outputs ++= chunk
      }

      assertTrue(outputs.result() == Chunk(10L, 20L, 30L))
    },
    test("contramap transforms inputs") {
      val doubler      = Scan.arr[Int, Int](_ * 2)
      val contramapped = doubler.contramap[Int](_ + 1)

      var state   = contramapped.init
      val outputs = Chunk.newBuilder[Int]

      List(1, 2, 3).foreach { i =>
        val (s2, chunk) = contramapped.step(state, i)
        state = s2
        outputs ++= chunk
      }

      assertTrue(outputs.result() == Chunk(4, 6, 8))
    },
    test("Category law: left identity") {
      val doubler = Scan.arr[Int, Int](_ * 2)
      val withId  = Scan.id[Int] >>> doubler

      val inputs = List(1, 2, 3)

      def runScan[S](s: Scan.Aux[Int, Int, S]): Chunk[Int] =
        var state   = s.init
        val outputs = Chunk.newBuilder[Int]
        inputs.foreach { i =>
          val (s2, chunk) = s.step(state, i)
          state = s2
          outputs ++= chunk
        }
        outputs ++= s.flush(state)
        outputs.result()

      assertTrue(runScan(doubler) == runScan(withId))
    },
    test("Category law: right identity") {
      val doubler = Scan.arr[Int, Int](_ * 2)
      val withId  = doubler >>> Scan.id[Int]

      val inputs = List(1, 2, 3)

      def runScan[S](s: Scan.Aux[Int, Int, S]): Chunk[Int] =
        var state   = s.init
        val outputs = Chunk.newBuilder[Int]
        inputs.foreach { i =>
          val (s2, chunk) = s.step(state, i)
          state = s2
          outputs ++= chunk
        }
        outputs ++= s.flush(state)
        outputs.result()

      assertTrue(runScan(doubler) == runScan(withId))
    },
    test("Associativity: (a >>> b) >>> c == a >>> (b >>> c)") {
      val a = Scan.arr[Int, Int](_ + 1)
      val b = Scan.arr[Int, Int](_ * 2)
      val c = Scan.arr[Int, Int](_ - 1)

      val left  = (a >>> b) >>> c
      val right = a >>> (b >>> c)

      val inputs = List(1, 2, 3)

      def runScan[S](s: Scan.Aux[Int, Int, S]): Chunk[Int] =
        var state   = s.init
        val outputs = Chunk.newBuilder[Int]
        inputs.foreach { i =>
          val (s2, chunk) = s.step(state, i)
          state = s2
          outputs ++= chunk
        }
        outputs ++= s.flush(state)
        outputs.result()

      assertTrue(runScan(left) == runScan(right))
    },
    test("map identity law") {
      val doubler = Scan.arr[Int, Int](_ * 2)
      val mapped  = doubler.map(identity[Int])

      val inputs = List(1, 2, 3)

      def runScan[S](s: Scan.Aux[Int, Int, S]): Chunk[Int] =
        var state   = s.init
        val outputs = Chunk.newBuilder[Int]
        inputs.foreach { i =>
          val (s2, chunk) = s.step(state, i)
          state = s2
          outputs ++= chunk
        }
        outputs.result()

      assertTrue(runScan(doubler) == runScan(mapped))
    },
    test("map composition law") {
      val doubler       = Scan.arr[Int, Int](_ * 2)
      val f: Int => Int = _ + 1
      val g: Int => Int = _ * 10

      val composed1 = doubler.map(f).map(g)
      val composed2 = doubler.map(f andThen g)

      val inputs = List(1, 2, 3)

      def runScan[S](s: Scan.Aux[Int, Int, S]): Chunk[Int] =
        var state   = s.init
        val outputs = Chunk.newBuilder[Int]
        inputs.foreach { i =>
          val (s2, chunk) = s.step(state, i)
          state = s2
          outputs ++= chunk
        }
        outputs.result()

      assertTrue(runScan(composed1) == runScan(composed2))
    },
  )
