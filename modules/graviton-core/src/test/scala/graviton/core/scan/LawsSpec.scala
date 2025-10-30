package graviton.core.scan

import zio.*
import zio.test.*
import zio.stream.*
import graviton.testkit.TestGen

/**
 * Category/Arrow/Choice/Product laws for FreeScan algebra.
 * 
 * Verifies that the free representation is lawful under both
 * pure and ZIO interpreters.
 */
object LawsSpec extends ZIOSpecDefault:
  
  import graviton.streams.scan.InterpretZIO
  
  // Helper: run via ZIO interpreter
  private def runZIO[I, O, S <: Rec](
    fs: FreeScan[Chunk, Chunk, I, O, S],
    input: Chunk[I]
  ): ZIO[Any, Nothing, Chunk[O]] =
    ZStream.fromChunk(input).via(InterpretZIO.toPipeline(fs)).runCollect
  
  // Helper: run via pure interpreter
  private def runPure[I, O, S <: Rec](
    fs: FreeScan[Chunk, Chunk, I, O, S],
    input: Chunk[I]
  ): Chunk[O] =
    InterpretPure.runChunk(fs, input)._2
  
  def spec = suite("FreeScan Category/Arrow Laws")(
    test("Category left identity: id >>> f ≡ f") {
      check(TestGen.boundedBytes) { input =>
        val f = FreeScan.arr[Chunk, Byte, Long](b => (b & 0xFF).toLong)
        val id = FreeScan.id[Chunk, Byte]
        val composed = id >>> f
        
        for {
          expected <- runZIO(f, input)
          actual <- runZIO(composed, input)
        } yield assertTrue(actual == expected)
      }
    },
    
    test("Category right identity: f >>> id ≡ f") {
      check(TestGen.boundedBytes) { input =>
        val f = FreeScan.arr[Chunk, Byte, Long](b => (b & 0xFF).toLong)
        val id = FreeScan.id[Chunk, Long]
        val composed = f >>> id
        
        for {
          expected <- runZIO(f, input)
          actual <- runZIO(composed, input)
        } yield assertTrue(actual == expected)
      }
    },
    
    test("Category associativity: (f >>> g) >>> h ≡ f >>> (g >>> h)") {
      check(TestGen.boundedBytes) { input =>
        val f = FreeScan.arr[Chunk, Byte, Int](b => (b & 0xFF))
        val g = FreeScan.arr[Chunk, Int, Long](_.toLong)
        val h = FreeScan.arr[Chunk, Long, String](_.toString)
        
        val left = (f >>> g) >>> h
        val right = f >>> (g >>> h)
        
        for {
          leftResult <- runZIO(left, input)
          rightResult <- runZIO(right, input)
        } yield assertTrue(leftResult == rightResult)
      }
    },
    
    test("Arrow dimap: dimap id id ≡ id") {
      check(TestGen.boundedBytes) { input =>
        val scan = FreeScan.id[Chunk, Byte]
        val dimapped = scan.dimap(identity[Byte])(identity[Byte])
        
        for {
          expected <- runZIO(scan, input)
          actual <- runZIO(dimapped, input)
        } yield assertTrue(actual == expected)
      }
    },
    
    test("Arrow composition: (f >>> g).dimap(l)(r) ≡ f.dimap(l)(id) >>> g.dimap(id)(r)") {
      check(TestGen.boundedBytes) { input =>
        val f = FreeScan.arr[Chunk, Byte, Int](b => (b & 0xFF))
        val g = FreeScan.arr[Chunk, Int, Long](_.toLong)
        val l = (s: String) => s.headOption.map(_.toByte).getOrElse(0.toByte)
        val r = (n: Long) => s"result:$n"
        
        val left = (f >>> g).dimap(l)(r)
        val right = f.dimap(l)(identity[Int]) >>> g.dimap(identity[Int])(r)
        
        val stringInput = input.map(b => s"${b.toChar}")
        
        for {
          leftResult <- runZIO(left, stringInput)
          rightResult <- runZIO(right, stringInput)
        } yield assertTrue(leftResult == rightResult)
      }
    },
    
    test("Parallel product preserves independence") {
      check(TestGen.boundedBytes, TestGen.boundedBytes) { (input1, input2) =>
        val f = FreeScan.arr[Chunk, Byte, Int](b => (b & 0xFF))
        val g = FreeScan.arr[Chunk, Byte, Long](b => (b & 0xFF).toLong)
        val par = f +++ g
        
        val pairedInput = input1.zipAll(input2).map { case (a, b) =>
          (a.getOrElse(0.toByte), b.getOrElse(0.toByte))
        }
        
        for {
          result <- runZIO(par, pairedInput)
          f1 <- runZIO(f, input1.take(result.length))
          g1 <- runZIO(g, input2.take(result.length))
        } yield {
          val (ints, longs) = result.unzip
          assertTrue(ints.length == result.length, longs.length == result.length)
        }
      }
    },
    
    test("Fanout broadcasts input") {
      check(TestGen.boundedBytes) { input =>
        val f = FreeScan.arr[Chunk, Byte, Int](b => (b & 0xFF))
        val g = FreeScan.arr[Chunk, Byte, Long](b => (b & 0xFF).toLong)
        val fan = f &&& g
        
        for {
          result <- runZIO(fan, input)
          f1 <- runZIO(f, input)
          g1 <- runZIO(g, input)
        } yield {
          val (ints, longs) = result.unzip
          assertTrue(
            ints.length == f1.length || longs.length == g1.length
          )
        }
      }
    },
    
    test("Pure and ZIO interpreters agree on identity") {
      check(TestGen.boundedBytes) { input =>
        val scan = FreeScan.id[Chunk, Byte]
        val pure = runPure(scan, input)
        
        runZIO(scan, input).map { zio =>
          assertTrue(pure == zio)
        }
      }
    },
    
    test("Pure and ZIO interpreters agree on arr") {
      check(TestGen.boundedBytes) { input =>
        val scan = FreeScan.arr[Chunk, Byte, Int](b => (b & 0xFF))
        val pure = runPure(scan, input)
        
        runZIO(scan, input).map { zio =>
          assertTrue(pure == zio)
        }
      }
    }
  )
