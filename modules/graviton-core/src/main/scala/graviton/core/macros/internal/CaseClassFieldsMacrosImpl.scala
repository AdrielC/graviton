package graviton.core.macros.internal

import graviton.core.macros.CaseClassFields
import hearth.*
import hearth.fp.effect.*

/** Cross-quotes macro logic for `CaseClassFields`. */
private[macros] trait CaseClassFieldsMacrosImpl { this: MacroCommons =>

  /** Derives `List[String]` of case class field names for `A`. */
  final def deriveNames[A: Type]: Expr[List[String]] =
    deriveOrFail[A]("CaseClassFields.names")

  private def deriveOrFail[A: Type](name: String): Expr[List[String]] =
    Log
      .namedScope(s"Derivation for $name") {
        Log.info(s"Macro expansion started at ${Environment.currentPosition.prettyPrint}") >>
          attemptAsCaseClass[A]
      }
      .runToExprOrFail(
        name,
        infoRendering = if (shouldWeLogDerivation) RenderFrom(Log.Level.Info) else DontRender,
      ) { (errorLogs, errors) =>
        val errorsStr = errors.toVector
          .map {
            case DerivationError.NotACaseClass(typeName) => s"Expected a case class, got $typeName"
            case e                                       => s"Unexpected error: ${e.getMessage}"
          }
          .mkString("\n")

        if (errorLogs.nonEmpty) {
          s"""Failed to derive $name:
             |$errorsStr
             |Error logs:
             |$errorLogs
             |""".stripMargin
        } else {
          s"""Failed to derive $name:
             |$errorsStr
             |""".stripMargin
        }
      }

  private def attemptAsCaseClass[A: Type]: MIO[Expr[List[String]]] =
    CaseClass.parse[A] match
      case Some(cc) =>
        implicit val StringType: Type[String] = Type.of[String]
        val fields                            = cc.caseFields.map(_.value.name)
        MIO.pure(Expr(fields))
      case None     =>
        MIO.fail(DerivationError.NotACaseClass(Type.prettyPrint[A]))

  /**
   * Enables logging if we either:
   *   - import `graviton.core.macros.CaseClassFields.debug.given`
   *   - have set scalac option `-Xmacro-settings:caseClassFields.logDerivation=true`
   */
  private def shouldWeLogDerivation: Boolean = {
    implicit val LogDerivation: Type[CaseClassFields.LogDerivation] = Type.of[CaseClassFields.LogDerivation]

    def logDerivationImported: Boolean = Expr.summonImplicit[CaseClassFields.LogDerivation].isDefined

    def logDerivationSetGlobally: Boolean =
      (for {
        data      <- Environment.typedSettings.toOption
        cfg       <- data.get("caseClassFields")
        shouldLog <- cfg.get("logDerivation").flatMap(_.asBoolean)
      } yield shouldLog).getOrElse(false)

    logDerivationImported || logDerivationSetGlobally
  }
}

private[macros] sealed trait DerivationError extends scala.util.control.NoStackTrace with Product with Serializable

private[macros] object DerivationError {
  final case class NotACaseClass(typeName: String) extends DerivationError
}
