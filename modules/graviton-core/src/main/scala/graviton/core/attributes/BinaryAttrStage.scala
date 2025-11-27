package graviton.core.attributes

import kyo.Record
import kyo.Record.Field
import kyo.internal.TypeIntersection
import scala.compiletime.summonInline

trait ValueWrapperStage[Wrapper[_]] extends Record.StageAs[[N, V] =>> Wrapper[V]]:
  inline def wrap[Name <: String, Value](field: Field[Name, Value]): Wrapper[Value]

  inline def stage[Name <: String, Value](field: Field[Name, Value]): Wrapper[Value] =
    wrap(field)

object BinaryAttrStage:

  inline def stageBase[Wrapper[_]](
    inline stage: ValueWrapperStage[Wrapper]
  )(
    using TypeIntersection[BinaryAttr.Base],
    Record.AsFields[BinaryAttr.Base],
  ): BinaryAttr.Rec[Wrapper] =
    Record
      .stage[BinaryAttr.Base]
      .apply[[N, V] =>> Wrapper[V]](stage)
      .asInstanceOf[BinaryAttr.Rec[Wrapper]]
