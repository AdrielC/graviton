package graviton.db

import zio.Scope
import zio.test.*

object CursorSpec extends ZIOSpecDefault {

  override def spec: Spec[TestEnvironment & Scope, Any] =
    suite("Cursor")(
      test("next advances offset and preserves totals") {
        val cursor = Cursor.initial.copy(offset = 0L, pageSize = 10L)
        val next   = cursor.next(5L)
        assertTrue(next.offset == 5L, next.pageSize == 10L)
      },
      test("withTotal combines totals using maximum") {
        val cursor  = Cursor.initial.copy(total = Some(Max(10L)))
        val updated = cursor.withTotal(Max(20L))
        assertTrue(updated.total.contains(Max(20L)))
      },
      test("differ patch composes") {
        val a     = Cursor.initial.copy(offset = 10L, total = Some(Max(50L)))
        val b     = a.next(10L).withTotal(Max(60L))
        val patch = Cursor.differ.diff(a, b)
        val out   = Cursor.differ.patch(patch)(a)
        assertTrue(out == b)
      },
    )
}
