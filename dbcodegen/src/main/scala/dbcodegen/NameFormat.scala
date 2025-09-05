package dbcodegen

object NameFormat {

  private val scalaKeywords = {
    // In Scala 3, the list of reserved keywords is available via the standard library.
    // See: https://docs.scala-lang.org/scala3/reference/changed-features/identifiers.html
    // We'll use the hardcoded set from the language spec.
    Set(
      "abstract", "case", "catch", "class", "def", "do", "else", "enum", "export", "extends",
      "false", "final", "finally", "for", "forSome", "given", "if", "implicit", "import", "inline",
      "lazy", "match", "new", "null", "object", "override", "package", "private", "protected",
      "return", "sealed", "super", "then", "this", "throw", "trait", "true", "try", "type", "val",
      "var", "while", "with", "yield", "using", "_"
    )
  }

  def sanitizeScalaName(rawName: String): String = {
    val name              = rawName.trim
    def isValidIdentifier = name.matches("^[a-zA-Z_][a-zA-Z0-9_]*$")
    if (name.isEmpty || (!scalaKeywords(name) && isValidIdentifier)) name else s"`$name`"
  }

  def toCamelCase(str: String): String  = caseConvert(_.toLowerCase, _.toLowerCase.capitalize, "", str)
  
  def toPascalCase(str: String): String = caseConvert(_.toLowerCase.capitalize, _.toLowerCase.capitalize, "", str)

  // Taken from: https://github.com/process-street/scala-encase
  private val caseSeparatorPattern = List(
    "\\s+",
    "_",
    "-",
    "(?<=[A-Z])(?=[A-Z][a-z])",
    "(?<=[^A-Z_-])(?=[A-Z])",
    "(?<=[A-Za-z])(?=[^A-Za-z])",
  ).mkString("|").r

  private def caseConvert(headTransform: String => String, tailTransform: String => String, sep: String, str: String): String = {
    val split  = caseSeparatorPattern.split(str)
    val result = split.take(1).map(headTransform) ++ split.drop(1).map(tailTransform)
    result.mkString(sep)
  }
}