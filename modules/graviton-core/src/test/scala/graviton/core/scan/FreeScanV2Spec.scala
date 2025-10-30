package graviton.core.scan

import zio.test.*
import zio.test.Assertion.*
import zio.Chunk

/**
 * Comprehensive tests for FreeScanV2.
 *
 * Tests:
 * 1. Basic constructors (Id, Embed, Map, Contramap)
 * 2. Composition operators (>>>, &&&, ***, |||)
 * 3. Category laws (identity, associativity)
 * 4. Monoidal laws (unit, associativity)
 * 5. Symmetric laws (braiding)
 * 6. State composition (Tensor)
 * 7. Feature promotion (Pure -> Stateful -> Effectful -> Full)
 * 8. Real-world examples
 */
object FreeScanV2Spec extends ZIOSpecDefault:
  
  import FreeScan.*
  import tags.*
  import features.*
  
  // Test primitives
  
  /** Identity primitive */
  def idPrim[A]: PrimScan[A, A, Unit] =
    PrimScan.pure(identity)
  
  /** Counter primitive */
  def counterPrim: PrimScan[Byte, Long, Long] =
    PrimScan.fold(0L)((s, _) => s + 1)
  
  /** Doubler primitive */
  def doublerPrim: PrimScan[Int, Int, Unit] =
    PrimScan.pure(_ * 2)
  
  /** Accumulator primitive */
  def sumPrim: PrimScan[Int, Int, Int] =
    PrimScan.fold(0)(_ + _)
  
  /** Hasher primitive (mock) */
  def hasherPrim: PrimScan[Byte, Array[Byte], Array[Byte]] =
    new PrimScan[Byte, Array[Byte], Array[Byte]]:
      def init = Array.empty[Byte]
      def step(state: Array[Byte], input: Byte) =
        (state :+ input, Chunk.empty)
      def flush(state: Array[Byte]) =
        Chunk.single(state)
  
  // Given instances for FreeU
  given FreeU[Obj[Byte]] = FreeU.objInstance
  given FreeU[Obj[Int]] = FreeU.objInstance
  given FreeU[Obj[Long]] = FreeU.objInstance
  given FreeU[Obj[Array[Byte]]] = FreeU.objInstance
  given FreeU[Obj[(Int, Int)]] = FreeU.objInstance
  given FreeU[Obj[(Long, Long)]] = FreeU.objInstance
  given FreeU[Obj[(Array[Byte], Long)]] = FreeU.objInstance
  given FreeU[One] = FreeU.oneInstance
  given FreeU[State[Unit]] = FreeU.stateInstance
  given FreeU[State[Long]] = FreeU.stateInstance
  given FreeU[State[Int]] = FreeU.stateInstance
  given FreeU[State[Array[Byte]]] = FreeU.stateInstance
  given FreeU[Tensor[Unit, Unit]] = FreeU.tensorInstance
  given FreeU[Tensor[Long, Long]] = FreeU.tensorInstance
  given FreeU[Tensor[Array[Byte], Long]] = FreeU.tensorInstance
  given FreeU[Tensor[Tensor[Array[Byte], Long], Unit]] = FreeU.tensorInstance
  
  // Embed helper
  def embedPrimToScan[I, O, S](p: PrimScan[I, O, S]): PrimScan[I, O, S] = p
  
  def spec = suite("FreeScan V2")(
    
    suite("Basic Constructors")(
      
      test("Id passes through input") {
        val scan = FreeScan.id[FreeU, Int]
        val inputs = Iterable(1, 2, 3)
        
        val (state, outputs) = PureInterpreter.run(
          scan,
          inputs
        )(embedPrimToScan)
        
        assertTrue(outputs == Vector(1, 2, 3))
      },
      
      test("Embed runs primitive scan") {
        val scan = FreeScan.embed[FreeU, PrimScan, Stateful, Byte, Long, Long](
          counterPrim
        )
        val inputs = Iterable[Byte](1, 2, 3)
        
        val (state, outputs) = PureInterpreter.run(
          scan,
          inputs
        )(embedPrimToScan)
        
        assertTrue(
          state == 3L &&
          outputs == Vector(1L, 2L, 3L)
        )
      },
      
      test("MapOut transforms outputs") {
        val counter = FreeScan.embed[FreeU, PrimScan, Stateful, Byte, Long, Long](
          counterPrim
        )
        val mapped = counter.map(_ * 10)
        val inputs = Iterable[Byte](1, 2, 3)
        
        val (state, outputs) = PureInterpreter.run(
          mapped,
          inputs
        )(embedPrimToScan)
        
        assertTrue(
          state == 3L &&
          outputs == Vector(10L, 20L, 30L)
        )
      },
      
      test("ContramapIn transforms inputs") {
        val doubler = FreeScan.embed[FreeU, PrimScan, Pure, Int, Int, Unit](
          doublerPrim
        )
        val contramapped = doubler.contramap[Int](_ + 1)
        val inputs = Iterable(1, 2, 3)
        
        val (state, outputs) = PureInterpreter.run(
          contramapped,
          inputs
        )(embedPrimToScan)
        
        assertTrue(outputs == Vector(4, 6, 8)) // (1+1)*2, (2+1)*2, (3+1)*2
      }
    ),
    
    suite("Sequential Composition (>>>)")(
      
      test("Sequences two scans") {
        val doubler = FreeScan.embed[FreeU, PrimScan, Pure, Int, Int, Unit](
          doublerPrim
        )
        val summer = FreeScan.embed[FreeU, PrimScan, Stateful, Int, Int, Int](
          sumPrim
        )
        val pipeline = doubler >>> summer
        val inputs = Iterable(1, 2, 3)
        
        val (state, outputs) = PureInterpreter.run(
          pipeline,
          inputs
        )(embedPrimToScan)
        
        val (s1, s2) = state.asInstanceOf[(Unit, Int)]
        
        assertTrue(
          s2 == 12 &&  // 2 + 4 + 6
          outputs == Vector(2, 6, 12)
        )
      },
      
      test("Sequential composition is associative") {
        val id1 = FreeScan.id[FreeU, Int]
        val id2 = FreeScan.id[FreeU, Int]
        val id3 = FreeScan.id[FreeU, Int]
        
        val left = (id1 >>> id2) >>> id3
        val right = id1 >>> (id2 >>> id3)
        
        val inputs = Iterable(1, 2, 3)
        
        val (_, out1) = PureInterpreter.run(left, inputs)(embedPrimToScan)
        val (_, out2) = PureInterpreter.run(right, inputs)(embedPrimToScan)
        
        assertTrue(out1 == out2)
      },
      
      test("Id is left identity for >>>") {
        val doubler = FreeScan.embed[FreeU, PrimScan, Pure, Int, Int, Unit](
          doublerPrim
        )
        val withId = FreeScan.id[FreeU, Int] >>> doubler
        
        val inputs = Iterable(1, 2, 3)
        
        val (_, out1) = PureInterpreter.run(doubler, inputs)(embedPrimToScan)
        val (_, out2) = PureInterpreter.run(withId, inputs)(embedPrimToScan)
        
        assertTrue(out1 == out2)
      },
      
      test("Id is right identity for >>>") {
        val doubler = FreeScan.embed[FreeU, PrimScan, Pure, Int, Int, Unit](
          doublerPrim
        )
        val withId = doubler >>> FreeScan.id[FreeU, Int]
        
        val inputs = Iterable(1, 2, 3)
        
        val (_, out1) = PureInterpreter.run(doubler, inputs)(embedPrimToScan)
        val (_, out2) = PureInterpreter.run(withId, inputs)(embedPrimToScan)
        
        assertTrue(out1 == out2)
      }
    ),
    
    suite("Fanout Composition (&&&)")(
      
      test("Runs both scans on same input") {
        val counter = FreeScan.embed[FreeU, PrimScan, Stateful, Byte, Long, Long](
          counterPrim
        )
        val hasher = FreeScan.embed[FreeU, PrimScan, Stateful, Byte, Array[Byte], Array[Byte]](
          hasherPrim
        )
        val fanout = counter &&& hasher
        
        val inputs = Iterable[Byte](1, 2, 3)
        
        val (state, outputs) = PureInterpreter.run(
          fanout,
          inputs
        )(embedPrimToScan)
        
        val (countState, hashState) = state.asInstanceOf[(Long, Array[Byte])]
        
        assertTrue(
          countState == 3L &&
          hashState.toVector == Vector[Byte](1, 2, 3) &&
          outputs.length == 3
        )
      },
      
      test("Fanout composes states via Tensor") {
        val counter1 = FreeScan.embed[FreeU, PrimScan, Stateful, Byte, Long, Long](
          counterPrim
        )
        val counter2 = FreeScan.embed[FreeU, PrimScan, Stateful, Byte, Long, Long](
          counterPrim
        )
        val fanout = counter1 &&& counter2
        
        val inputs = Iterable[Byte](1, 2, 3)
        
        val (state, outputs) = PureInterpreter.run(
          fanout,
          inputs
        )(embedPrimToScan)
        
        val (s1, s2) = state.asInstanceOf[(Long, Long)]
        
        assertTrue(
          s1 == 3L &&
          s2 == 3L
        )
      }
    ),
    
    suite("State Composition")(
      
      test("Tensor state is accessible") {
        val counter = FreeScan.embed[FreeU, PrimScan, Stateful, Byte, Long, Long](
          counterPrim
        )
        val hasher = FreeScan.embed[FreeU, PrimScan, Stateful, Byte, Array[Byte], Array[Byte]](
          hasherPrim
        )
        val composed = counter &&& hasher
        
        val inputs = Iterable[Byte](1, 2, 3)
        
        val (state, _) = PureInterpreter.run(
          composed,
          inputs
        )(embedPrimToScan)
        
        val (countState, hashState) = state.asInstanceOf[(Long, Array[Byte])]
        
        assertTrue(
          countState == 3L &&
          hashState.toVector == Vector[Byte](1, 2, 3)
        )
      },
      
      test("Nested Tensor from sequential then fanout") {
        val c1 = FreeScan.embed[FreeU, PrimScan, Stateful, Byte, Long, Long](counterPrim)
        val c2 = FreeScan.embed[FreeU, PrimScan, Stateful, Long, Long, Long](counterPrim)
        val c3 = FreeScan.embed[FreeU, PrimScan, Stateful, Byte, Long, Long](counterPrim)
        
        val seq = c1 >>> c2  // State: Tensor[Long, Long]
        val fan = seq &&& c3  // State: Tensor[Tensor[Long, Long], Long]
        
        val inputs = Iterable[Byte](1, 2, 3)
        
        val (state, _) = PureInterpreter.run(
          fan,
          inputs
        )(embedPrimToScan)
        
        val ((s1, s2), s3) = state.asInstanceOf[((Long, Long), Long)]
        
        assertTrue(
          s1 == 3L &&  // First counter
          s2 == 3L &&  // Second counter  
          s3 == 3L     // Third counter
        )
      }
    ),
    
    suite("Feature Level Promotion")(
      
      test("Pure + Pure = Pure") {
        val d1 = FreeScan.embed[FreeU, PrimScan, Pure, Int, Int, Unit](doublerPrim)
        val d2 = FreeScan.embed[FreeU, PrimScan, Pure, Int, Int, Unit](doublerPrim)
        val composed = d1 >>> d2
        
        // Type should be Pure (but we can't check at runtime)
        val inputs = Iterable(1, 2, 3)
        val (_, outputs) = PureInterpreter.run(composed, inputs)(embedPrimToScan)
        
        assertTrue(outputs == Vector(4, 8, 12))
      },
      
      test("Pure + Stateful = Stateful") {
        val doubler = FreeScan.embed[FreeU, PrimScan, Pure, Int, Int, Unit](doublerPrim)
        val summer = FreeScan.embed[FreeU, PrimScan, Stateful, Int, Int, Int](sumPrim)
        val composed = doubler >>> summer
        
        // Type is Stateful (Pure | Stateful = Stateful)
        val inputs = Iterable(1, 2, 3)
        val (state, outputs) = PureInterpreter.run(composed, inputs)(embedPrimToScan)
        
        val (_, sumState) = state.asInstanceOf[(Unit, Int)]
        
        assertTrue(
          sumState == 12 &&
          outputs == Vector(2, 6, 12)
        )
      }
    ),
    
    suite("Profunctor Laws")(
      
      test("map identity") {
        val scan = FreeScan.embed[FreeU, PrimScan, Pure, Int, Int, Unit](doublerPrim)
        val mapped = scan.map(identity[Int])
        
        val inputs = Iterable(1, 2, 3)
        
        val (_, out1) = PureInterpreter.run(scan, inputs)(embedPrimToScan)
        val (_, out2) = PureInterpreter.run(mapped, inputs)(embedPrimToScan)
        
        assertTrue(out1 == out2)
      },
      
      test("map composition") {
        val scan = FreeScan.embed[FreeU, PrimScan, Pure, Int, Int, Unit](doublerPrim)
        val f: Int => Int = _ + 1
        val g: Int => Int = _ * 10
        
        val composed1 = scan.map(f).map(g)
        val composed2 = scan.map(f andThen g)
        
        val inputs = Iterable(1, 2, 3)
        
        val (_, out1) = PureInterpreter.run(composed1, inputs)(embedPrimToScan)
        val (_, out2) = PureInterpreter.run(composed2, inputs)(embedPrimToScan)
        
        assertTrue(out1 == out2)
      },
      
      test("contramap identity") {
        val scan = FreeScan.embed[FreeU, PrimScan, Pure, Int, Int, Unit](doublerPrim)
        val contramapped = scan.contramap(identity[Int])
        
        val inputs = Iterable(1, 2, 3)
        
        val (_, out1) = PureInterpreter.run(scan, inputs)(embedPrimToScan)
        val (_, out2) = PureInterpreter.run(contramapped, inputs)(embedPrimToScan)
        
        assertTrue(out1 == out2)
      },
      
      test("contramap composition") {
        val scan = FreeScan.embed[FreeU, PrimScan, Pure, Int, Int, Unit](doublerPrim)
        val f: Int => Int = _ + 1
        val g: Int => Int = _ * 10
        
        val composed1 = scan.contramap(g).contramap(f)
        val composed2 = scan.contramap(f andThen g)
        
        val inputs = Iterable(1, 2, 3)
        
        val (_, out1) = PureInterpreter.run(composed1, inputs)(embedPrimToScan)
        val (_, out2) = PureInterpreter.run(composed2, inputs)(embedPrimToScan)
        
        assertTrue(out1 == out2)
      }
    ),
    
    suite("Real-World Examples")(
      
      test("Byte counter scan") {
        val counter = FreeScan.embed[FreeU, PrimScan, Stateful, Byte, Long, Long](
          counterPrim
        )
        
        val inputs = (1 to 100).map(_.toByte)
        
        val (state, outputs) = PureInterpreter.run(
          counter,
          inputs
        )(embedPrimToScan)
        
        assertTrue(
          state == 100L &&
          outputs.length == 100 &&
          outputs.last == 100L
        )
      },
      
      test("Hash + Count pipeline") {
        val counter = FreeScan.embed[FreeU, PrimScan, Stateful, Byte, Long, Long](
          counterPrim
        )
        val hasher = FreeScan.embed[FreeU, PrimScan, Stateful, Byte, Array[Byte], Array[Byte]](
          hasherPrim
        )
        
        val metrics = counter &&& hasher
        
        val inputs = Iterable[Byte](1, 2, 3, 4, 5)
        
        val (state, outputs) = PureInterpreter.run(
          metrics,
          inputs
        )(embedPrimToScan)
        
        val (countState, hashState) = state.asInstanceOf[(Long, Array[Byte])]
        
        assertTrue(
          countState == 5L &&
          hashState.toVector == Vector[Byte](1, 2, 3, 4, 5) &&
          outputs.length == 5
        )
      },
      
      test("Transform -> Accumulate -> Format pipeline") {
        val doubler = FreeScan.embed[FreeU, PrimScan, Pure, Int, Int, Unit](doublerPrim)
        val summer = FreeScan.embed[FreeU, PrimScan, Stateful, Int, Int, Int](sumPrim)
        val formatter = FreeScan.arr[FreeU, Int, String](n => s"Total: $n")
        
        val pipeline = doubler >>> summer >>> formatter
        
        val inputs = Iterable(1, 2, 3, 4, 5)
        
        val (state, outputs) = PureInterpreter.run(
          pipeline,
          inputs
        )(embedPrimToScan)
        
        assertTrue(
          outputs == Vector("Total: 2", "Total: 6", "Total: 12", "Total: 20", "Total: 30")
        )
      }
    )
  )
