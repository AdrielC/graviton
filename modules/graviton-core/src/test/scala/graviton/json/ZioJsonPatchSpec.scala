package graviton.json

import diffson.jsonpatch.JsonPatch
import diffson.jsonpatch.Remove
import diffson.jsonpointer.Pointer
import diffson.jsonmergepatch.JsonMergePatch
import zio.Chunk
import zio.json.ast.Json
import zio.test.*

import graviton.json.ZioJsonPatch.given

object ZioJsonPatchSpec extends ZIOSpecDefault:

  private val one   = Json.Num(java.math.BigDecimal.valueOf(1L))
  private val two   = Json.Num(java.math.BigDecimal.valueOf(2L))
  private val hello = Json.Str("graviton")

  def spec =
    suite("ZioJsonPatch")(
      test("diff followed by apply reproduces the updated document") {
        val original = Json.Obj(Chunk("name" -> hello, "version" -> one))
        val updated  = Json.Obj(Chunk("name" -> hello, "version" -> two, "active" -> Json.Bool(true)))
        for
          patch  <- ZioJsonPatch.diff(original, updated)
          result <- ZioJsonPatch.applyPatch(original, patch)
        yield assertTrue(result == updated)
      },
      test("applyPatch surfaces failures when operations cannot be completed") {
        val document = Json.Obj(Chunk("value" -> one))
        val patch    = JsonPatch[Json](
          List(Remove[Json](Pointer.Root / "missing"))
        )
        ZioJsonPatch
          .applyPatch(document, patch)
          .either
          .map(result => assert(result)(Assertion.isLeft(Assertion.isSubtype[ZioJsonPatch.PatchError.ApplyFailed](Assertion.anything))))
      },
      test("applyMergePatch merges nested objects and scalar values") {
        val document = Json.Obj(
          Chunk(
            "name" -> hello,
            "meta" -> Json.Obj(Chunk("version" -> one)),
          )
        )
        val merge    = JsonMergePatch.Object[Json](
          Map(
            "meta"   -> Json.Obj(Chunk("status" -> Json.Str("stable"))),
            "active" -> Json.Bool(true),
          )
        )
        val expected = Json.Obj(
          Chunk(
            "name"   -> hello,
            "meta"   -> Json.Obj(Chunk("version" -> one, "status" -> Json.Str("stable"))),
            "active" -> Json.Bool(true),
          )
        )
        ZioJsonPatch.applyMergePatch(document, merge).map(result => assertTrue(result == expected))
      },
    )
