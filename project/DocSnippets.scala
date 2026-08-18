import sbt._
import sbt.util.Logger
import java.io.File

case class DocSnippet(
  id: String,
  docPath: String,
  snippetPath: String,
  language: String = "scala"
) {
  val startMarker: String = s"<!-- snippet:$id:start -->"
  val endMarker: String   = s"<!-- snippet:$id:end -->"
}

object DocSnippetTasks {
  private def normalize(value: String): String =
    value.replace("\r\n", "\n")

  private def ensureTrailingNewline(value: String): String =
    if (value.endsWith("\n")) value else value + "\n"

  private def loadSnippet(file: File): String =
    ensureTrailingNewline(normalize(IO.read(file)))

  private def snippetBlock(snippet: DocSnippet, code: String): String =
    "\n```" + snippet.language + "\n" + code + "```\n"

  private def locateSections(content: String, snippet: DocSnippet, docFile: File): (String, String, String) = {
    val startIdx = content.indexOf(snippet.startMarker)
    if (startIdx < 0)
      sys.error(s"Start marker '${snippet.startMarker}' not found in ${docFile.getPath}")
    val afterStart = startIdx + snippet.startMarker.length
    val endIdx     = content.indexOf(snippet.endMarker, afterStart)
    if (endIdx < 0)
      sys.error(s"End marker '${snippet.endMarker}' not found in ${docFile.getPath}")
    val before  = content.substring(0, afterStart)
    val between = content.substring(afterStart, endIdx)
    val after   = content.substring(endIdx)
    (before, between, after)
  }

  private def syncSingle(snippet: DocSnippet, root: File, log: Logger, checkOnly: Boolean): Boolean = {
    val docFile     = new File(root, snippet.docPath)
    val snippetFile = new File(root, snippet.snippetPath)
    if (!docFile.exists())
      sys.error(s"Documentation file ${docFile.getPath} not found for snippet '${snippet.id}'")
    if (!snippetFile.exists())
      sys.error(s"Snippet source ${snippetFile.getPath} not found for snippet '${snippet.id}'")

    val docContent               = normalize(IO.read(docFile))
    val (before, between, after) = locateSections(docContent, snippet, docFile)
    val expectedBlock            = snippetBlock(snippet, loadSnippet(snippetFile))

    val matches = normalize(between) == normalize(expectedBlock)
    if (!matches && !checkOnly) {
      val updated = before + expectedBlock + after
      IO.write(docFile, updated)
      log.info(s"Updated snippet '${snippet.id}' in ${snippet.docPath}")
    }
    matches
  }

  def sync(snippets: Seq[DocSnippet], root: File, log: Logger): Unit =
    snippets.foreach(snippet => syncSingle(snippet, root, log, checkOnly = false))

  def check(snippets: Seq[DocSnippet], root: File, log: Logger): Unit = {
    val stale = snippets.filterNot(snippet => syncSingle(snippet, root, log, checkOnly = true))
    if (stale.nonEmpty) {
      stale.foreach { snippet =>
        log.error(s"Snippet '${snippet.id}' is out of sync. Run `sbt syncDocSnippets`.")
      }
      sys.error("Documentation snippets are stale.")
    }
  }
}
