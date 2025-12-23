package graviton.core.attributes

import kyo.Record
import kyo.Record.Field
import kyo.internal.TypeIntersection

trait ValueWrapperStage[Wrapper[_]] extends Record.StageAs[[N, V] =>> Wrapper[V]]:
  inline def wrap[Name <: String, Value](field: Field[Name, Wrapper[Value]]): Wrapper[Value]

  inline def stage[Name <: String, Value](field: Field[Name, Wrapper[Value]]): Wrapper[Value] =
    wrap(field)

object BinaryAttrStage:

  transparent inline def stageBase[Wrapper[_]](
    inline stage: ValueWrapperStage[Wrapper]
  )(
    using ti: TypeIntersection[BinaryAttr.Base],
    af: Record.AsFields[BinaryAttr.Base],
  ) =
    Record
      .stage[BinaryAttr.Base](stage)
