package dbcodegen

object NameFormat {
  private val nonWordRegex = "[^A-Za-z0-9_]+".r

  def toCamelCase(name: String): String = {
    val parts = splitWords(name)
    (parts.headOption.getOrElse("") + parts.drop(1).map(_.capitalize).mkString).stripPrefix("_")
  }

  def toPascalCase(name: String): String = splitWords(name).map(_.capitalize).mkString

  def sanitizeScalaName(name: String): String = {
    val cleaned = name.replaceAll(nonWordRegex.regex, "_")
    val startsWithDigit = cleaned.headOption.exists(_.isDigit)
    val base = if (startsWithDigit) s"_${cleaned}" else cleaned
    if (isScalaKeyword(base)) s"`${base}`" else base
  }

  private def splitWords(name: String): List[String] =
    name.replaceAll(nonWordRegex.regex, " ").trim.split("[\u0020_]+").toList.filter(_.nonEmpty).map(_.toLowerCase)

  private val scalaKeywords: Set[String] = Set(
    "abstract","case","catch","class","def","do","else","enum","export","extends","false","final","finally",
    "for","given","if","implicit","import","lazy","match","new","null","object","override","package","private",
    "protected","return","sealed","super","then","this","throw","trait","true","try","type","val","var","while",
    "with","yield"
  )

  private def isScalaKeyword(s: String): Boolean = scalaKeywords.contains(s)
}


