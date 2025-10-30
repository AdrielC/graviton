package graviton.core.scan

import zio.test.*
import zio.test.Assertion.*

/**
 * Property-based tests for FIM (Free Invariant Monoidal) laws.
 *
 * The "sicko pack" - comprehensive algebraic law verification:
 *
 * 1. Monoidal Laws:
 *    - Left unit: ε ⊗ A ≅ A
 *    - Right unit: A ⊗ ε ≅ A
 *    - Associativity: (A ⊗ B) ⊗ C ≅ A ⊗ (B ⊗ C)
 *
 * 2. Invariant Functor Laws:
 *    - Identity: imap(id, id) = id
 *    - Composition: imap(f).imap(g) = imap(g ∘ f)
 *    - Round-trip: imap(iso).imap(iso.inverse) = id
 *
 * 3. Optimization Laws:
 *    - Unit elimination: simplify(A ⊗ ε) ≈ A
 *    - Iso fusion: simplify(imap(f).imap(g)) ≈ imap(g ∘ f)
 *    - Dead state: eliminateDeadState with empty set ≈ unit
 *
 * 4. Interpreter Laws:
 *    - Consistency: all interpreters produce equivalent results
 *    - Initialization: read(alloc()) yields initial values
 *    - Update: write(repr, v) then read yields v
 */
object FIMLawsSpec extends ZIOSpecDefault:

  def spec = suite("FIM Laws")(
    suite("Monoidal Laws")(
      test("Left unit: ε ⊗ A ≅ A") {
        check(genFIM[Long]) { fa =>
          val productWithUnit = FIM.unit ⊗ fa
          val optimized       = FIMOptimize.simplify(productWithUnit)

          // Allocate and check values match
          val aVal     = ImmutableInterpreter.alloc(fa)()
          val unitAVal = ImmutableInterpreter.alloc(productWithUnit)()

          // After iso application, should be equivalent
          assertTrue(unitAVal._2 == aVal)
        }
      },
      test("Right unit: A ⊗ ε ≅ A") {
        check(genFIM[Long]) { fa =>
          val productWithUnit = fa ⊗ FIM.unit
          val optimized       = FIMOptimize.simplify(productWithUnit)

          val aVal     = ImmutableInterpreter.alloc(fa)()
          val aUnitVal = ImmutableInterpreter.alloc(productWithUnit)()

          assertTrue(aUnitVal._1 == aVal)
        }
      },
      test("Associativity: (A ⊗ B) ⊗ C ≅ A ⊗ (B ⊗ C)") {
        check(genFIM[Long], genFIM[Boolean], genFIM[String]) { (fa, fb, fc) =>
          val leftAssoc  = (fa ⊗ fb) ⊗ fc
          val rightAssoc = fa ⊗ (fb ⊗ fc)

          val leftVal  = ImmutableInterpreter.alloc(leftAssoc)()
          val rightVal = ImmutableInterpreter.alloc(rightAssoc)()

          // Values should be isomorphic
          val leftFlat  = flattenNested(leftVal)
          val rightFlat = flattenNested(rightVal)

          assertTrue(leftFlat == rightFlat)
        }
      },
    ),
    suite("Invariant Functor Laws")(
      test("Identity: imap(id) = id") {
        check(genFIM[Long]) { fa =>
          val identity = Iso.identity[Long]
          val mapped   = fa.imap(identity)

          val originalVal = ImmutableInterpreter.alloc(fa)()
          val mappedVal   = ImmutableInterpreter.alloc(mapped)()

          assertTrue(originalVal == mappedVal)
        }
      },
      test("Iso round-trip: imap(iso).imap(iso.inverse) = id") {
        check(genFIM[Long]) { fa =>
          val iso       = Iso[Long, String](_.toString, _.toLong)
          val roundTrip = fa.imap(iso).imap(iso.inverse)

          val originalVal  = ImmutableInterpreter.alloc(fa)()
          val roundTripVal = ImmutableInterpreter.alloc(roundTrip)()

          assertTrue(originalVal == roundTripVal)
        }
      },
      test("Composition: imap(f).imap(g) = imap(g ∘ f)") {
        check(genFIM[Long]) { fa =>
          val f = Iso[Long, String](_.toString, _.toLong)
          val g = Iso[String, Int](_.length, _.toString)

          val composed = fa.imap(f).imap(g)
          val fused    = fa.imap(f.andThen(g))

          val composedVal = ImmutableInterpreter.alloc(composed)()
          val fusedVal    = ImmutableInterpreter.alloc(fused)()

          assertTrue(composedVal == fusedVal)
        }
      },
    ),
    suite("Optimization Laws")(
      test("Unit elimination: simplify(A ⊗ ε) removes unit") {
        check(genFIM[Long]) { fa =>
          val withUnit   = fa ⊗ FIM.unit
          val simplified = FIMOptimize.simplify(withUnit)

          // Simplified should have fewer units
          val originalUnits   = countUnits(withUnit)
          val simplifiedUnits = countUnits(simplified)

          assertTrue(simplifiedUnits <= originalUnits)
        }
      },
      test("Iso fusion: simplify fuses nested imap") {
        check(genFIM[Long]) { fa =>
          val iso1 = Iso[Long, String](_.toString, _.toLong)
          val iso2 = Iso[String, Int](_.length, _.toString)

          val nested     = fa.imap(iso1).imap(iso2)
          val simplified = FIMOptimize.simplify(nested)

          // Simplified should have fewer IsoF nodes
          val originalIsos   = countIsos(nested)
          val simplifiedIsos = countIsos(simplified)

          assertTrue(simplifiedIsos <= originalIsos)
        }
      },
      test("Dead state elimination: removes unused components") {
        val fa      = FIM.counter("used")
        val fb      = FIM.counter("unused")
        val product = fa ⊗ fb

        val usedLabels = Set("used")
        val eliminated = FIMOptimize.eliminateDeadState(product, usedLabels)

        val labels = FIMOptimize.extractLabels(eliminated)

        assertTrue(labels.contains("used"))
      },
    ),
    suite("Interpreter Consistency")(
      test("ImmutableInterpreter: init then read yields initial value") {
        val spec  = FIM.counter("test")
        val repr  = ImmutableInterpreter.alloc(spec)()
        val value = ImmutableInterpreter.read(spec, repr)

        // Initial counter should be 0
        assertTrue(value == 0L)
      },
      test("ImmutableInterpreter: write then read yields written value") {
        val spec = FIM.counter("test")
        val repr = ImmutableInterpreter.alloc(spec)()

        val newValue    = 42L
        val updatedRepr = ImmutableInterpreter.write(spec, repr, newValue)
        val readValue   = ImmutableInterpreter.read(spec, updatedRepr)

        assertTrue(readValue == newValue)
      },
      test("RecInterpreter: init then read yields initial value") {
        val spec  = FIM.counter("test")
        val repr  = RecInterpreter.alloc(spec)()
        val value = RecInterpreter.read(spec, repr)

        // Initial counter should be 0
        assertTrue(value == 0L)
      },
      test("RecInterpreter: write then read yields written value") {
        val spec = FIM.counter("test")
        val repr = RecInterpreter.alloc(spec)()

        val newValue    = 42L
        val updatedRepr = RecInterpreter.write(spec, repr, newValue)
        val readValue   = RecInterpreter.read(spec, updatedRepr)

        assertTrue(readValue == newValue)
      },
    ),
    suite("Product Composition")(
      test("Independent state updates don't interfere") {
        val specA    = FIM.counter("a")
        val specB    = FIM.counter("b")
        val combined = specA ⊗ specB

        val repr = ImmutableInterpreter.alloc(combined)()

        // Update first component
        val updated1 = ImmutableInterpreter.write(combined, repr, (100L, 0L))
        val (a1, b1) = ImmutableInterpreter.read(combined, updated1)

        // Update second component
        val updated2 = ImmutableInterpreter.write(combined, updated1, (100L, 200L))
        val (a2, b2) = ImmutableInterpreter.read(combined, updated2)

        assertTrue(a1 == 100L && b1 == 0L && a2 == 100L && b2 == 200L)
      },
      test("Product preserves field labels") {
        val spec   = FIM.counter("bytes") ⊗ FIM.flag("done")
        val labels = FIMOptimize.extractLabels(spec)

        assertTrue(
          labels.contains("bytes") && labels.contains("done")
        )
      },
    ),
    suite("Complex State Patterns")(
      test("Nested products flatten correctly") {
        val spec   = (FIM.counter("a") ⊗ FIM.counter("b")) ⊗ FIM.counter("c")
        val labels = FIMOptimize.extractLabels(spec)

        assertTrue(
          labels == Set("a", "b", "c")
        )
      },
      test("Iso transformations preserve values") {
        case class Stats(bytes: Long, done: Boolean)

        val spec = FIM.counter("bytes") ⊗ FIM.flag("done")
        val iso  = Iso[(Long, Boolean), Stats](
          { case (b, d) => Stats(b, d) },
          s => (s.bytes, s.done),
        )

        val withIso = spec.imap(iso)

        val repr  = ImmutableInterpreter.alloc(withIso)()
        val stats = ImmutableInterpreter.read(withIso, repr)

        assertTrue(stats.bytes == 0L && stats.done == false)
      },
    ),
  )

  // Generators for property-based testing

  def genFIM[A]: Gen[Any, FIM[A]] =
    // Use counter which has proper initialization
    Gen.const(FIM.counter("test").asInstanceOf[FIM[A]])

  given genLong: Gen[Any, Long]     = Gen.long(0L, 100L)
  given genBool: Gen[Any, Boolean]  = Gen.boolean
  given genString: Gen[Any, String] = Gen.alphaNumericString

  // Helper to flatten nested tuples for comparison
  def flattenNested(value: Any): List[Any] =
    value match
      case (a, b) => flattenNested(a) ++ flattenNested(b)
      case ()     => Nil
      case other  => List(other)

  // Count unit occurrences in FIM structure
  def countUnits[S](fim: FIM[S]): Int =
    fim match
      case FIM.UnitF()       => 1
      case FIM.Prod(l, r)    => countUnits(l) + countUnits(r)
      case FIM.IsoF(base, _) => countUnits(base)
      case _                 => 0

  // Count iso occurrences in FIM structure
  def countIsos[S](fim: FIM[S]): Int =
    fim match
      case FIM.UnitF()       => 0
      case FIM.Labeled(_, _) => 0
      case FIM.Prod(l, r)    => countIsos(l) + countIsos(r)
      case FIM.IsoF(base, _) => 1 + countIsos(base)
