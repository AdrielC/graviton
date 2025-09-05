package torrent.detect

import java.nio.file.{ Files, Path, Paths, StandardCopyOption }

import torrent.utils.SecurityUtils

import zio.*
import zio.stream.*

class HintBasedFileRenamer(detector: ContentTypeDetector) {

  /** Attempts to rename the file if its content doesn't match its extension */
  def renameIfMismatched(pathStr: String): ZIO[Scope, Throwable, Path] =
    for {
      pathValidation <- ZIO.fromEither(
                          SecurityUtils
                            .validatePathV(pathStr)
                            .toEither
                            .left
                            .map(errs => new IllegalArgumentException(errs.mkString(", ")))
                        )
      path            = pathValidation.value
      filename        = path.getFileName.toString
      maybeExtension  = getExtension(filename)

      isReadable <- ZIO.attemptBlocking(Files.isReadable(path))
      _          <- ZIO.fail(new java.io.FileNotFoundException(s"File not readable: $path")).unless(isReadable)

      // Detect actual content type
      (detectionOpt, _)       <- detector.detectFromFile(path.toString)
      actualType: ContentType <-
        ZIO.fromOption(detectionOpt.map(_.contentType)).orElseFail(new Exception("Could not detect content type"))

      // If extension exists, compare it to actual type
      newPath <- maybeExtension match {
                   case Some(ext) if !matchesExtension(ext, actualType) =>
                     val correctedName = replaceExtension(filename, actualType)
                     val newPath       = path.resolveSibling(correctedName)
                     ZIO.attemptBlocking {
                       Files.move(path, newPath, StandardCopyOption.REPLACE_EXISTING)
                     }
                   case _                                               =>
                     ZIO.succeed(path) // No rename needed
                 }
    } yield newPath

  private def getExtension(filename: String): Option[String] = {
    val idx = filename.lastIndexOf('.')
    if (idx > 0 && idx < filename.length - 1) Some(filename.substring(idx + 1).toLowerCase)
    else None
  }

  private def matchesExtension(ext: String, contentType: ContentType): Boolean = (ext, contentType) match {
    case ("pdf", ContentType.PDF)                => true
    case ("png", ContentType.PNG)                => true
    case ("mp4", ContentType.MP4)                => true
    case ("json", ContentType.JSON)              => true
    case ("docx", ContentType.DOCX)              => true
    case ("xlsx", ContentType.XLSX)              => true
    case ("rtf", ContentType.RTF)                => true
    case ("tif", ContentType.TIF)                => true
    case ("csv", ContentType.CSV)                => true
    case ("txt" | "text", ContentType.PlainText) => true
    case _                                       => false
  }

  private def replaceExtension(filename: String, newType: ContentType): String = {
    val nameWithoutExt = filename.takeWhile(_ != '.')
    val newExt         = contentTypeToExtension(newType)
    s"$nameWithoutExt.$newExt"
  }

  private def contentTypeToExtension(contentType: ContentType): String = contentType match {
    case ContentType.PDF       => "pdf"
    case ContentType.PNG       => "png"
    case ContentType.MP4       => "mp4"
    case ContentType.JSON      => "json"
    case ContentType.DOCX      => "docx"
    case ContentType.XLSX      => "xlsx"
    case ContentType.RTF       => "rtf"
    case ContentType.TIF       => "tif"
    case ContentType.CSV       => "csv"
    case ContentType.PlainText => "txt"
  }
}
