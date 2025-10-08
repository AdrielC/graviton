package graviton.arrow

import zio.test.*

object FreeArrowSpec extends ZIOSpecDefault:

  sealed trait Prim[-I, +O, C <: Tuple]
  object Prim:
    final case class Stateless[I, O](run: I => O) extends Prim[I, O, EmptyTuple]
    final case class Emit[I, O](run: I => O)      extends Prim[I, O, Tuple1[Capability.Emit]]

  sealed trait Capability
  object Capability:
    sealed trait Emit extends Capability

  given BottomOf[Int] with
    def value: Int = 0

  given FreeArrow.Interpreter[Prim, Tuple2, Either, Function] with
    val bundle: ArrowBundle.Aux[Function, Tuple2, Either] = summon[ArrowBundle[Function]]

    def interpret[I, O, C <: Tuple](prim: Prim[I, O, C]): I => O = prim match
      case Prim.Stateless(run) => run
      case Prim.Emit(run)      => run

  def spec = suite("FreeArrowSpec")(
    test("composes stateless primitives") {
      val a        = FreeArrow.embed[Prim, Tuple2, Either, Int, Int, EmptyTuple](Prim.Stateless((i: Int) => i + 1))
      val b        = FreeArrow.embed[Prim, Tuple2, Either, Int, Int, EmptyTuple](Prim.Stateless((i: Int) => i * 2))
      val program  = a >>> b
      val compiled = program.compile
      assertTrue(compiled(2) == 6)
    },
    test("fanout duplicates work") {
      val inc      = FreeArrow.embed[Prim, Tuple2, Either, Int, Int, EmptyTuple](Prim.Stateless((i: Int) => i + 1))
      val dec      = FreeArrow.embed[Prim, Tuple2, Either, Int, Int, EmptyTuple](Prim.Stateless((i: Int) => i - 1))
      val program  = inc &&& dec
      val compiled = program.compile
      assertTrue(compiled(10) == ((11, 9)))
    },
    test("coproduct mapping") {
      val ints     = FreeArrow.embed[Prim, Tuple2, Either, Int, Int, EmptyTuple](Prim.Stateless((i: Int) => i + 1))
      val doubles  = FreeArrow.embed[Prim, Tuple2, Either, Double, Double, EmptyTuple](Prim.Stateless((d: Double) => d * 0.5))
      val program  = ints +++ doubles
      val compiled = program.compile
      assertTrue(compiled(Left(3)) == Left(4)) &&
      assertTrue(compiled(Right(8.0)) == Right(4.0))
    },
    test("fanin merges either branches") {
      val ints     = FreeArrow.embed[Prim, Tuple2, Either, Int, Int, EmptyTuple](Prim.Stateless((i: Int) => i + 1))
      val chars    = FreeArrow.embed[Prim, Tuple2, Either, String, Int, EmptyTuple](Prim.Stateless((s: String) => s.length))
      val program  = ints ||| chars
      val compiled = program.compile
      assertTrue(compiled(Left(3)) == 4) &&
      assertTrue(compiled(Right("zio")) == 3)
    },
    test("zero arrow produces absorbing result") {
      val zero     = FreeArrow.zero[Prim, Tuple2, Either, Int, Int]
      val compiled = zero.compile
      assertTrue(compiled(42) == 0)
    },
    test("capabilities accumulate through composition") {
      val stateless = FreeArrow.embed[Prim, Tuple2, Either, Int, Int, EmptyTuple](Prim.Stateless((i: Int) => i))
      val emit      = FreeArrow.embed[Prim, Tuple2, Either, Int, Int, Tuple1[Capability.Emit]](Prim.Emit((i: Int) => i))
      val program   = stateless >>> emit
      val _         = summon[program.Caps =:= Tuple1[Capability.Emit]]
      assertTrue(program.compile(1) == 1)
    },
  )
