package torrent.utils

import java.nio.file.{ Path, Paths }

import scala.util.Try

import zio.*
import zio.prelude.*

/**
 * Security utilities for input validation and sanitization
 */
private[torrent] object SecurityUtils {

  // Opaque type for validated file path
  opaque type ValidPath <: Path = Path
  object ValidPath {
    def apply(path: Path): ValidPath           = path
    extension (vp:  ValidPath) def value: Path = vp
  }

  // Opaque type for validated filename
  opaque type ValidFilename <: String = String
  object ValidFilename {
    def apply(name: String): ValidFilename           = name
    extension (vf:  ValidFilename) def value: String = vf
  }

  // Opaque type for validated file size
  opaque type ValidFileSize <: Long = Long
  object ValidFileSize {
    def apply(size: Long): ValidFileSize           = size
    extension (vfs: ValidFileSize) def value: Long = vfs
  }

  /**
   * Individual validation functions for path components
   */
  def validatePathFormat(pathStr: String): Validation[String, Path] =
    Validation
      .fromTry(Try(Paths.get(pathStr).normalize()))
      .mapError(e => Option(e.getMessage).getOrElse(s"Invalid path: $pathStr"))

  def validatePathTraversal(path: Path): Validation[String, Path] = {
    val pathString = path.toString
    val errors     = List(
      Option.when(pathString.contains(".."))("Path contains '..'"),
      Option.when(pathString.contains("~"))("Path contains '~'")
    ).flatten

    if (errors.isEmpty) Validation.succeed(path)
    else Validation.fail(errors.mkString(", "))
  }

  def validateDangerousPaths(path: Path): Validation[String, Path] = {
    val normalizedPathString = path.toString.replace('\\', '/').toLowerCase

    val dangerousUnixPaths      = List("/etc/", "/var/", "/proc/", "/sys/", "/dev/", "/boot/", "/root/")
    val dangerousWindowsPaths   =
      List("/windows/system32/", "/windows/syswow64/", "/program files/", "/program files (x86)/")
    val dangerousDirectoryNames = List("system32", "syswow64", "program files", "program files (x86)")

    val errors = List(
      Option.when(dangerousUnixPaths.exists(normalizedPathString.startsWith))(
        "Path is a dangerous Unix system directory"
      ),
      Option.when(
        dangerousWindowsPaths.exists(normalizedPathString.contains) ||
          dangerousDirectoryNames.exists(name => normalizedPathString.contains("/" + name + "/"))
      )("Path is a dangerous Windows system directory")
    ).flatten

    if (errors.isEmpty) Validation.succeed(path)
    else Validation.fail(errors.mkString(", "))
  }

  /**
   * Validates and normalizes a file path to prevent path traversal attacks
   * (Validation version)
   */
  def validatePathV(pathStr: String): Validation[String, ValidPath] = {
    val errors = collection.mutable.ListBuffer.empty[String]

    // Validate path format using Try
    val pathTry = Try(Paths.get(pathStr).normalize())
    val path    = Validation
      .fromTry(pathTry)
      .fold(
        _ => {
          errors += s"Invalid path format: $pathStr"
          None
        },
        path => Some(path)
      )

    // Validate path traversal
    val pathString = path.map(_.toString).getOrElse("")
    if (pathString.contains("..")) errors += "Path contains '..'"
    if (pathString.contains("~")) errors += "Path contains '~'"

    // Validate dangerous paths
    val normalizedPathString = pathString.replace('\\', '/').toLowerCase
    val dangerousUnixPaths   = List("/etc/", "/var/", "/proc/", "/sys/", "/dev/", "/boot/", "/root/")
    if (dangerousUnixPaths.exists(normalizedPathString.startsWith)) {
      errors += "Path is a dangerous Unix system directory"
    }

    val dangerousWindowsPaths   =
      List("/windows/system32/", "/windows/syswow64/", "/program files/", "/program files (x86)/")
    val dangerousDirectoryNames = List("system32", "syswow64", "program files", "program files (x86)")
    if (
      dangerousWindowsPaths.exists(normalizedPathString.contains) ||
      dangerousDirectoryNames.exists(name => normalizedPathString.contains("/" + name + "/"))
    ) {
      errors += "Path is a dangerous Windows system directory"
    }

    if (errors.isEmpty && path.isDefined) Validation.succeed(ValidPath(path.get))
    else Validation.fail(errors.mkString(", "))
  }

  // Thin alias for backward compatibility
  def validatePath(pathStr: String) = validatePathV(pathStr)

  /**
   * Individual validation functions for filename components
   */
  def validateFilenameNotNull(filename: String): Validation[String, String] =
    Validation.fromOption(Option(filename)).mapError(_ => "Filename cannot be null")

  def validateFilenameNotEmpty(filename: String): Validation[String, String] =
    if (filename.trim.isEmpty) Validation.fail("Filename cannot be empty")
    else Validation.succeed(filename.trim)

  def validateFilenameReservedNames(filename: String): Validation[String, String] = {
    val reservedNames = Set(
      "CON",
      "PRN",
      "AUX",
      "NUL",
      "COM1",
      "COM2",
      "COM3",
      "COM4",
      "COM5",
      "COM6",
      "COM7",
      "COM8",
      "COM9",
      "LPT1",
      "LPT2",
      "LPT3",
      "LPT4",
      "LPT5",
      "LPT6",
      "LPT7",
      "LPT8",
      "LPT9"
    )

    Validation.fromPredicateWith(s"Invalid filename: $filename")(filename)(f =>
      !reservedNames.contains(f.trim.toUpperCase())
    )
  }

  def validateFilenameCharacters(filename: String): Validation[String, String] = {
    val errors = List(
      Option.when(filename.contains(".."))("Filename contains '..'"),
      Option.when(filename.contains("/"))("Filename contains '/'"),
      Option.when(filename.contains("\\"))("Filename contains '\\'"),
      Option.when(filename.startsWith("."))("Filename starts with '.'"),
      Option.when(filename.contains("~"))("Filename contains '~'"),
      Option.when(filename.matches(".*[<>:\"|?*].*"))("Filename contains invalid characters (< > : \" | ? *)")
    ).flatten

    if (errors.isEmpty) Validation.succeed(filename)
    else Validation.fail(errors.mkString(", "))
  }

  def validateFilenameLength(filename: String): Validation[String, String] =
    if (filename.length > 255) Validation.fail("Filename greater than max size: 255")
    else Validation.succeed(filename)

  /**
   * Validates a filename to prevent directory traversal and other attacks
   * (Validation version)
   */
  def validateFilenameV(filename: String): Validation[String, ValidFilename] = {
    // Validate reserved names
    val reservedNames = Set(
      "CON",
      "PRN",
      "AUX",
      "NUL",
      "COM1",
      "COM2",
      "COM3",
      "COM4",
      "COM5",
      "COM6",
      "COM7",
      "COM8",
      "COM9",
      "LPT1",
      "LPT2",
      "LPT3",
      "LPT4",
      "LPT5",
      "LPT6",
      "LPT7",
      "LPT8",
      "LPT9"
    )

    for {
      cleanName <- Validation
                     .fromOption(Option(filename))
                     .mapError(_ => "Filename cannot be null")
                     .map(_.trim)

      nonEmpty = Validation.fromPredicateWith("Filename cannot be empty")(cleanName)(_.nonEmpty)

      reserved = Validation.fromPredicateWith(s"Invalid filename: $filename")(cleanName.toUpperCase())(
                   !reservedNames.contains(_)
                 )

      // Add character validation using Validation.fromPredicateWith
      noTraversal    = Validation.fromPredicateWith("Filename contains '..'")(cleanName)(!_.contains(".."))
      noSlashes      = Validation.fromPredicateWith("Filename contains path separators")(cleanName)(f =>
                         !f.contains("/") && !f.contains("\\")
                       )
      noHidden       = Validation.fromPredicateWith("Filename starts with '.'")(cleanName)(!_.startsWith("."))
      noTilde        = Validation.fromPredicateWith("Filename contains '~'")(cleanName)(!_.contains("~"))
      noInvalidChars = Validation.fromPredicateWith("Filename contains invalid characters (< > : \" | ? *)")(cleanName)(
                         !_.matches(".*[<>:\"|?*].*")
                       )
      validLength    = Validation.fromPredicateWith("Filename greater than max size: 255")(cleanName)(_.length <= 255)

      _ <-
        Validation.validate(nonEmpty, reserved, noTraversal, noSlashes, noHidden, noTilde, noInvalidChars, validLength)

    } yield ValidFilename(cleanName)
  }

  /**
   * Individual validation functions for file size
   */
  def validateFileSizeMin(size: Long, min: Long): Validation[String, Long] =
    Validation.fromPredicateWith(s"File size $size is less than minimum allowed $min")(size)(_ >= min)

  def validateFileSizeMax(size: Long, max: Long): Validation[String, Long] =
    Validation.fromPredicateWith(s"File size $size is greater than maximum allowed $max")(size)(_ <= max)

  /**
   * Validates file size to prevent resource exhaustion
   */
  def validateFileSizeV(
    size: Long,
    min:  Long = 0,
    max:  Long = 100 * 1024 * 1024
  ): Validation[String, ValidFileSize] =
    Validation.validateWith(
      validateFileSizeMin(size, min),
      validateFileSizeMax(size, max)
    )((a, _) => ValidFileSize(a))

  /**
   * Sanitizes input to prevent XSS and other injection attacks
   */
  def sanitizeInput(input: String): String =
    Option(input).fold("") {
      _.replaceAll("[<>]", "")         // Remove HTML/XML tags only (preserve quotes)
        .replaceAll("javascript:", "") // Remove javascript: protocol
        .replaceAll("data:", "")       // Remove data: protocol
        .trim
    }

  /**
   * Validates content type to prevent dangerous file types
   */
  def validateContentType(contentType: String): Validation[String, String] = {
    val normalizedType = Option(contentType).map(_.trim.toLowerCase).getOrElse("")

    if (normalizedType.isEmpty) Validation.fail("Content type cannot be null or empty")
    else {
      val dangerousTypes = Set(
        "application/x-executable",
        "application/x-msdownload",
        "application/x-msi",
        "application/x-msdos-program"
      )

      if (dangerousTypes.contains(normalizedType)) {
        Validation.fail(s"Dangerous content type: $contentType")
      } else {
        Validation.succeed(normalizedType)
      }
    }
  }

  /**
   * Validates URL to prevent SSRF attacks
   */
  def validateUrl(url: String): Validation[String, String] = {
    val normalizedUrl = Option(url).map(_.trim.toLowerCase).getOrElse("")

    if (normalizedUrl.isEmpty) Validation.fail("URL cannot be null or empty")
    else {
      val dangerousProtocols = Set("file://", "ftp://", "gopher://", "dict://", "ldap://")

      val hasDangerousProtocol = dangerousProtocols.exists(normalizedUrl.startsWith)

      if (hasDangerousProtocol) Validation.fail(s"Dangerous protocol in URL: $url")
      else {
        // More precise localhost detection - check if it's actually a localhost URL
        val isLocalhost = normalizedUrl match {
          case url if url.startsWith("http://localhost") || url.startsWith("https://localhost") => true
          case url if url.startsWith("http://127.0.0.1") || url.startsWith("https://127.0.0.1") => true
          case url if url.startsWith("http://::1") || url.startsWith("https://::1")             => true
          case url if url.startsWith("http://192.168.") || url.startsWith("https://192.168.")   => true
          case url if url.startsWith("http://10.") || url.startsWith("https://10.")             => true
          case url if url.startsWith("http://172.16.") || url.startsWith("https://172.16.")     => true
          case url if url.startsWith("http://172.17.") || url.startsWith("https://172.17.")     => true
          case url if url.startsWith("http://172.18.") || url.startsWith("https://172.18.")     => true
          case url if url.startsWith("http://172.19.") || url.startsWith("https://172.19.")     => true
          case url if url.startsWith("http://172.20.") || url.startsWith("https://172.20.")     => true
          case url if url.startsWith("http://172.21.") || url.startsWith("https://172.21.")     => true
          case url if url.startsWith("http://172.22.") || url.startsWith("https://172.22.")     => true
          case url if url.startsWith("http://172.23.") || url.startsWith("https://172.23.")     => true
          case url if url.startsWith("http://172.24.") || url.startsWith("https://172.24.")     => true
          case url if url.startsWith("http://172.25.") || url.startsWith("https://172.25.")     => true
          case url if url.startsWith("http://172.26.") || url.startsWith("https://172.26.")     => true
          case url if url.startsWith("http://172.27.") || url.startsWith("https://172.27.")     => true
          case url if url.startsWith("http://172.28.") || url.startsWith("https://172.28.")     => true
          case url if url.startsWith("http://172.29.") || url.startsWith("https://172.29.")     => true
          case url if url.startsWith("http://172.30.") || url.startsWith("https://172.30.")     => true
          case url if url.startsWith("http://172.31.") || url.startsWith("https://172.31.")     => true
          case _                                                                                => false
        }

        if (isLocalhost) Validation.fail(s"Localhost/private IP not allowed: $url")
        else Validation.succeed(normalizedUrl)
      }
    }
  }
}
