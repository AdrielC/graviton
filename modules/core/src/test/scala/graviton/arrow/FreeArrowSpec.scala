package graviton.arrow

import zio.test.*

import scala.NamedTuple

sealed trait Prim[-I, +O, C <: NamedTuple.AnyNamedTuple]
object Prim:
  final case class Stateless[I, O](run: I => O)                               extends Prim[I, O, NamedTuple.Empty]
  final case class WithCaps[I, O, C <: NamedTuple.AnyNamedTuple](run: I => O) extends Prim[I, O, C]

sealed trait Capability
object Capability:
  sealed trait Emit  extends Capability
  sealed trait Log   extends Capability
  sealed trait State extends Capability

given BottomOf[Int] with
  def value: Int = 0

given BottomOf[String] with
  def value: String = ""

given FreeArrow.Interpreter[Prim, Tuple2, Either, Function] = new FreeArrow.Interpreter[Prim, Tuple2, Either, Function]:

  def interpret[I, O, C <: NamedTuple.AnyNamedTuple](prim: Prim[I, O, C]): I => O = prim match
    case Prim.Stateless(run) => run
    case Prim.WithCaps(run)  => run

object FreeArrowSpec extends ZIOSpecDefault:

  def spec = suite("FreeArrowSpec")(
    test("composes stateless primitives") {
      val a        = FreeArrow.embed[Prim, Tuple2, Either, Int, Int, NamedTuple.Empty](Prim.Stateless((i: Int) => i + 1))
      val b        = FreeArrow.embed[Prim, Tuple2, Either, Int, Int, NamedTuple.Empty](Prim.Stateless((i: Int) => i * 2))
      val program  = a >>> b
      val compiled = program.compile[Function]
      assertTrue(compiled.apply(2) == 6)
    },
    test("fanout duplicates work") {
      val inc      = FreeArrow.embed[Prim, Tuple2, Either, Int, Int, NamedTuple.Empty](Prim.Stateless((i: Int) => i + 1))
      val dec      = FreeArrow.embed[Prim, Tuple2, Either, Int, Int, NamedTuple.Empty](Prim.Stateless((i: Int) => i - 1))
      val program  = inc &&& dec
      val compiled = program.compile[Function]
      assertTrue(compiled(10) == ((11, 9)))
    },
    test("coproduct mapping") {
      val ints     = FreeArrow.embed[Prim, Tuple2, Either, Int, Int, NamedTuple.Empty](Prim.Stateless((i: Int) => i + 1))
      val doubles  = FreeArrow.embed[Prim, Tuple2, Either, Double, Double, NamedTuple.Empty](Prim.Stateless((d: Double) => d * 0.5))
      val program  = ints +++ doubles
      val compiled = program.compile[Function]
      assertTrue(compiled(Left(3)) == Left(4)) &&
      assertTrue(compiled(Right(8.0)) == Right(4.0))
    },
    test("fanin merges either branches") {
      val ints     = FreeArrow.embed[Prim, Tuple2, Either, Int, Int, NamedTuple.Empty](Prim.Stateless((i: Int) => i + 1))
      val chars    = FreeArrow.embed[Prim, Tuple2, Either, String, Int, NamedTuple.Empty](Prim.Stateless((s: String) => s.length))
      val program  = ints ||| chars
      val compiled = program.compile[Function]
      assertTrue(compiled.apply(Left(3)) == 4) &&
      assertTrue(compiled.apply(Right("zio")) == 3)
    },
    test("zero arrow produces absorbing result") {
      val zero     = FreeArrow.zero[Prim, Tuple2, Either, Int, Int]
      val compiled = zero.compile[Function]
      assertTrue(compiled(42) == 0)
    },
    test("capabilities accumulate through composition") {
      val stateless = FreeArrow.embed[Prim, Tuple2, Either, Int, Int, NamedTuple.Empty](Prim.Stateless((i: Int) => i))
      type EmitCaps = NamedTuple.NamedTuple[("emit" *: EmptyTuple), (Capability.Emit *: EmptyTuple)]
      val emit    =
        FreeArrow.embed[Prim, Tuple2, Either, Int, Int, EmitCaps](Prim.WithCaps[Int, Int, EmitCaps]((i: Int) => i))
      val program = stateless >>> emit
      val _       = summon[program.Caps =:= EmitCaps]
      assertTrue(program.compile[Function](1) == 1)
    },
    test("capabilities deduplicate repeated requirements") {
      type EmitCap = NamedTuple.NamedTuple[("emit" *: EmptyTuple), (Capability.Emit *: EmptyTuple)]
      val emit    = FreeArrow.embed[Prim, Tuple2, Either, Int, Int, EmitCap](Prim.WithCaps[Int, Int, EmitCap]((i: Int) => i + 1))
      val program = emit >>> emit
      type expected = NamedTuple.Concat[EmitCap, EmitCap]
      val _ = summon[program.Caps =:= expected]
      assertTrue(program.compile[Function].apply(1) == 3)
    },
    test("parallel composition handles independent inputs") {
      val inc      = FreeArrow.embed[Prim, Tuple2, Either, Int, Int, NamedTuple.Empty](Prim.Stateless((i: Int) => i + 1))
      val dbl      = FreeArrow.embed[Prim, Tuple2, Either, Int, Int, NamedTuple.Empty](Prim.Stateless((i: Int) => i * 2))
      val program  = inc *** dbl
      val compiled = program.compile[Function]
      assertTrue(compiled((2, 3)) == ((3, 6)))
    },
    test("first and second lift a program across tuple components") {
      val inc    = FreeArrow.embed[Prim, Tuple2, Either, Int, Int, NamedTuple.Empty](Prim.Stateless((i: Int) => i + 1))
      val first  = inc.first[String]
      val second = inc.second[String]
      assertTrue(first.compile[Function].apply((1, "zio")) == (2, "zio")) &&
      assertTrue(second.compile[Function].apply(("zio", 1)) == (("zio", 2)))
    },
    test("left and right lift a program across either components") {
      val length = FreeArrow.embed[Prim, Tuple2, Either, String, Int, NamedTuple.Empty](Prim.Stateless((s: String) => s.length))
      val left   = length.left[Double]
      val right  = length.right[Double]
      assertTrue(left.compile[Function].apply(Left("zio")) == Left(3)) &&
      assertTrue(right.compile[Function].apply(Right("zio")) == Right(3))
    },
    test("dimap transforms inputs and outputs in place") {
      val core     = FreeArrow.embed[Prim, Tuple2, Either, Int, Int, NamedTuple.Empty](Prim.Stateless((i: Int) => i * 2))
      val program  = core.dimap[String, String](_.toInt)(o => s"$o!")
      val compiled = program.compile[Function]
      assertTrue(compiled.apply("5") == "10!")
    },
    test("injections build canonical sum constructors") {
      val inl = FreeArrow.inl[Prim, Tuple2, Either, Int, String]
      val inr = FreeArrow.inr[Prim, Tuple2, Either, Int, String]
      assertTrue(inl.compile[Function].apply(1) == Left(1)) &&
      assertTrue(inr.compile[Function].apply("zio") == Right("zio"))
    },
    test("zero arrow is a left absorber for composition") {
      val zero      = FreeArrow.zero[Prim, Tuple2, Either, Int, String]
      val stateless = FreeArrow.id[Prim, Tuple2, Either, String]
      val program   = zero >>> stateless
      assertTrue(program.compile[Function].apply(10) == "")
    },
    test("capabilities combine across fanout and coproduct operations") {
      type EmitCap = NamedTuple.NamedTuple[("emit" *: EmptyTuple), (Capability.Emit *: EmptyTuple)]
      type LogCap  = NamedTuple.NamedTuple[("log" *: EmptyTuple), (Capability.Log *: EmptyTuple)]
      val emit      = FreeArrow.embed[Prim, Tuple2, Either, Int, Int, EmitCap](Prim.WithCaps[Int, Int, EmitCap](identity))
      val log       = FreeArrow.embed[Prim, Tuple2, Either, Int, Int, LogCap](Prim.WithCaps[Int, Int, LogCap](identity))
      val fanout    = emit &&& log
      val _         = summon[fanout.Caps =:= NamedTuple.Concat[EmitCap, LogCap]]
      val coproduct = emit +++ log
      summon[coproduct.Caps =:= NamedTuple.Concat[EmitCap, LogCap]]
      assertTrue(fanout.compile[Function].apply(3) == ((3, 3))) &&
      assertTrue(coproduct.compile[Function].apply(Left(3)) == Left(3))
    },
    test("iso nodes expose forward compilation while retaining union capabilities") {
      type EmitCap = NamedTuple.NamedTuple[("emitIso" *: EmptyTuple), (Capability.Emit *: EmptyTuple)]
      type LogCap  = NamedTuple.NamedTuple[("logIso" *: EmptyTuple), (Capability.Log *: EmptyTuple)]
      val forward  = FreeArrow.embed[Prim, Tuple2, Either, Int, Int, EmitCap](Prim.WithCaps[Int, Int, EmitCap](identity))
      val backward = FreeArrow.embed[Prim, Tuple2, Either, Int, Int, LogCap](Prim.WithCaps[Int, Int, LogCap](identity))
      val iso      = FreeArrow.iso(forward, backward)
      val _        = summon[iso.Caps =:= NamedTuple.Concat[EmitCap, LogCap]]
      assertTrue(iso.compile[Function].apply(7) == 7)
    },
    test("complex pipeline combining sums and products produces expected result") {
      val trim     = FreeArrow.embed[Prim, Tuple2, Either, String, String, NamedTuple.Empty](Prim.Stateless(_.trim))
      val parse    = FreeArrow.embed[Prim, Tuple2, Either, String, Int, NamedTuple.Empty](Prim.Stateless(_.toInt))
      val double   = FreeArrow.embed[Prim, Tuple2, Either, Int, Int, NamedTuple.Empty](Prim.Stateless(_ * 2))
      val left     = (trim >>> parse)
      val right    = double
      val routed   = left +++ right
      val finalize = FreeArrow.embed[Prim, Tuple2, Either, Either[Int, Int], Int, NamedTuple.Empty](
        Prim.Stateless((e: Either[Int, Int]) => e.fold(_ + 1, _ - 1))
      )
      val program  = routed >>> finalize
      assertTrue(program.compile[Function].apply(Left(" 21 ")) == 22) &&
      assertTrue(program.compile[Function].apply(Right(10)) == 19)
    },
  )
