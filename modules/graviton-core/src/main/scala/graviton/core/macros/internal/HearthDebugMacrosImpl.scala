package graviton.core.macros.internal

import hearth.*
import hearth.fp.effect.*

/** Cross-quotes macro logic for `HearthDebug`. */
private[macros] trait HearthDebugMacrosImpl { this: MacroCommons =>

  final def typeNameExpr[A: Type]: Expr[String] =
    Log
      .namedScope("Derivation for HearthDebug.typeName") {
        Log.info(s"Macro expansion started at ${Environment.currentPosition.prettyPrint}") >>
          MIO.pure(Expr(Type.prettyPrint[A]))
      }
      .runToExprOrFail("HearthDebug.typeName", infoRendering = DontRender) { (errorLogs, errors) =>
        val errorsStr = errors.toVector.map(_.getMessage).mkString("\n")
        if (errorLogs.nonEmpty) s"$errorsStr\n$errorLogs" else errorsStr
      }
}
