package graviton.arrow

import scala.collection.mutable
import scala.quoted.*

private[arrow] object StateNaming:

  private val counters = mutable.HashMap.empty[(String, String), Int]

  inline def derive(inline fallback: String = "state"): String = ${ deriveImpl('fallback) }

  private def deriveImpl(fallbackExpr: Expr[String])(using Quotes): Expr[String] =
    import quotes.reflect.*
    val pos      = Position.ofMacroExpansion
    val filePath = pos.sourceFile.jpath.map(_.toString).getOrElse(pos.sourceFile.path)
    val base     = extractName(pos).getOrElse(fallbackExpr.valueOrAbort).trim match
      case "" => fallbackExpr.valueOrAbort
      case s  => sanitize(s)
    val next = register(filePath, base)
    Expr(next)

  private def register(file: String, base: String): String =
    counters.synchronized {
      val key   = (file, base)
      val count = counters.getOrElse(key, 0)
      counters.update(key, count + 1)
      if count == 0 then base else s"${base}_${count}"
    }

  private def sanitize(name: String): String =
    val lowered = name.replaceAll("[^A-Za-z0-9_]", "_")
    if lowered.nonEmpty then lowered else "state"

  private def extractName(using Quotes)(pos: quotes.reflect.Position): Option[String] =
    val source = pos.sourceFile.content.mkString
    val upto   = source.substring(0, pos.start)
    val regex  = """(?s)(?:lazy\s+)?val\s+([A-Za-z_]\w*)""".r
    regex.findAllMatchIn(upto).toList.lastOption.map(_.group(1))
