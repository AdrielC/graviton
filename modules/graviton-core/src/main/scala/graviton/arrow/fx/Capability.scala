package graviton.arrow.fx

import kyo.Record
import kyo.Record.`~`
import kyo.internal.{Inliner, TypeIntersection}
import scala.compiletime.summonInline

/** Base capability entry that contributes a named state cell to an effect row. */
trait Capability[K <: String & Singleton, S]:
  type Key   = K
  type State = S

object Capability:
  type Aux[K <: String & Singleton, S] = Capability[K, S]

/** Predefined capability helpers. */
object Capabilities:

  trait StateSlot[Name <: String & Singleton, S] extends Capability[Name, S]

  object StateSlot:
    def apply[Name <: String & Singleton, S]: StateSlot[Name, S] =
      new StateSlot[Name, S] {}

  trait Leftovers[S] extends Capability["leftovers", S]

  object Leftovers:
    def apply[S]: Leftovers[S] = new Leftovers[S] {}

  trait EarlyStop[Reason] extends Capability["earlyStop", Option[Reason]]

  object EarlyStop:
    def apply[R]: EarlyStop[R] = new EarlyStop[R] {}

/** Utilities for deriving state layouts from capability rows. */
object CapRow:

  type AnyCapability = Capability[? <: String & Singleton, ?]

  type FieldOf[F] = (F & AnyCapability) match
    case Capability[key, state] => key ~ state
    case _                      => Any

  type FieldsOf[Fx] = TypeIntersection[Fx]#Map[[F] =>> FieldOf[F]]

  type StateRecord[Fx] = Record[FieldsOf[Fx]]

  object StateRecord:
    def empty[Fx](using builder: StateBuilder[Fx]): StateRecord[Fx] =
      builder.empty

  trait StateBuilder[Fx]:
    def empty: StateRecord[Fx]

  object StateBuilder:
    inline given derived[Fx](using ti: TypeIntersection[Fx]): StateBuilder[Fx] =
      new StateBuilder[Fx]:
        def empty: StateRecord[Fx] =
          TypeIntersection
            .inlineAll[Fx](DefaultStateInliner)
            .foldLeft(Record.empty: Record[Any])(_ & _)
            .asInstanceOf[StateRecord[Fx]]
  end StateBuilder

  private object DefaultStateInliner extends Inliner[Record[Any]]:
    inline def apply[T]: Record[Any] =
      summonInline[DefaultState[T & AnyCapability]].value.asInstanceOf[Record[Any]]

  trait DefaultState[C <: AnyCapability]:
    def value: Record[FieldOf[C]]

  object DefaultState:
    inline def apply[C <: AnyCapability](using ds: DefaultState[C]): DefaultState[C] = ds
end CapRow
