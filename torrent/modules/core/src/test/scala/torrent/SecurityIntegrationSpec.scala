package torrent

import torrent.utils.SecurityUtils

import zio.*
import zio.test.*

// scalafix:off

object SecurityIntegrationSpec extends ZIOSpecDefault {

  def spec = suite("Security Integration Tests")(
    suite("File Upload Security")(
      test("should reject malicious filenames during upload") {
        val maliciousNames = List(
          "../../../etc/passwd",
          "file<script>alert('xss')</script>.exe",
          "CON",
          "file|with|pipes.txt",
          "file:with:colons.txt"
        )

        for {
          results <- ZIO.foreach(maliciousNames) { name =>
                       SecurityUtils.validateFilenameV(name).toZIO.exit
                     }
        } yield assertTrue(results.forall(_.isFailure))
      },
      test("should accept safe filenames during upload") {
        val safeNames = List(
          "document.pdf",
          "image.jpg",
          "data.csv",
          "report.docx",
          "presentation.pptx"
        )

        for {
          results <- ZIO.foreach(safeNames) { name =>
                       SecurityUtils.validateFilenameV(name).toZIO
                     }
        } yield assertTrue(results.map(_.value) == safeNames)
      },
      test("should validate file sizes before processing") {
        val largeFileSize = 200 * 1024 * 1024L // 200MB
        val safeFileSize  = 50 * 1024 * 1024L  // 50MB

        for {
          largeResult <- SecurityUtils.validateFileSizeV(largeFileSize).toZIO.exit
          safeResult  <- SecurityUtils.validateFileSizeV(safeFileSize).toZIO
        } yield assertTrue(
          largeResult.isFailure,
          safeResult.value == safeFileSize
        )
      }
    ),
    suite("Content Type Detection Security")(
      test("should reject dangerous content types") {
        val dangerousTypes = List(
          "application/x-executable",
          "application/x-msdownload",
          "application/x-msi",
          "application/x-msdos-program"
        )

        for {
          results <- ZIO.foreach(dangerousTypes) { contentType =>
                       SecurityUtils.validateContentType(contentType).toZIO.exit
                     }
        } yield assertTrue(results.forall(_.isFailure))
      },
      test("should accept safe content types") {
        val safeTypes = List(
          "application/pdf",
          "image/jpeg",
          "text/plain",
          "application/json",
          "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        )

        for {
          results <- ZIO.foreach(safeTypes) { contentType =>
                       SecurityUtils.validateContentType(contentType).toZIO
                     }
        } yield assertTrue(results == safeTypes)
      }
    ),
    suite("Path Validation Security")(
      test("should prevent path traversal attacks") {
        val traversalPaths = List(
          "../../../etc/passwd",
          "..\\..\\..\\Windows\\System32\\config\\SAM",
          "~/ssh/id_rsa",
          "/etc/shadow",
          "/var/log/auth.log"
        )

        for {
          results <- ZIO.foreach(traversalPaths) { path =>
                       SecurityUtils.validatePathV(path).toZIO.exit
                     }
        } yield assertTrue(results.forall(_.isFailure))
      },
      test("should accept safe relative paths") {
        val safePaths = List(
          "documents/report.pdf",
          "images/photo.jpg",
          "data/export.csv",
          "backups/archive.zip"
        )

        for {
          results <- ZIO.foreach(safePaths) { path =>
                       SecurityUtils.validatePathV(path).toZIO
                     }
        } yield assertTrue(results.nonEmpty)
      }
    ),
    suite("Input Sanitization")(
      test("should sanitize user input before processing") {
        val maliciousInputs = List(
          "<script>alert('xss')</script>",
          "javascript:alert('xss')",
          "data:text/html,<script>alert('xss')</script>",
          "file:///etc/passwd"
        )

        val expectedSanitized = List(
          "scriptalert('xss')/script",
          "alert('xss')",
          "text/html,scriptalert('xss')/script",
          "file:///etc/passwd" // This should be rejected by URL validation
        )

        for {
          results <- ZIO.foreach(maliciousInputs) { input =>
                       ZIO.succeed(SecurityUtils.sanitizeInput(input))
                     }
        } yield assertTrue(results == expectedSanitized)
      },
      test("should preserve safe input") {
        val safeInputs = List(
          "Hello, this is a safe document",
          "Report for Q4 2023",
          "Data analysis results",
          "Meeting notes from yesterday"
        )

        for {
          results <- ZIO.foreach(safeInputs) { input =>
                       ZIO.succeed(SecurityUtils.sanitizeInput(input))
                     }
        } yield assertTrue(results == safeInputs)
      }
    ),
    suite("URL Validation Security")(
      test("should reject dangerous URLs") {
        val dangerousUrls = List(
          "file:///etc/passwd",
          "ftp://malicious.com",
          "gopher://malicious.com",
          "http://localhost:8080",
          "http://127.0.0.1:3000",
          "http://192.168.1.1"
        )

        for {
          results <- ZIO.foreach(dangerousUrls) { url =>
                       SecurityUtils.validateUrl(url).toZIO.exit
                     }
        } yield assertTrue(results.forall(_.isFailure))
      },
      test("should accept safe URLs") {
        val safeUrls = List(
          "https://example.com",
          "http://api.github.com",
          "https://docs.scala-lang.org",
          "https://zio.dev"
        )

        for {
          results <- ZIO.foreach(safeUrls) { url =>
                       SecurityUtils.validateUrl(url).toZIO
                     }
        } yield assertTrue(results == safeUrls)
      }
    ),
    suite("Real-world Security Scenarios")(
      test("should handle complete file upload workflow securely") {
        val filename    = "document.pdf"
        val contentType = "application/pdf"
        val fileSize    = 1024 * 1024L // 1MB

        for {
          // Step 1: Validate filename
          validatedFilename <- SecurityUtils.validateFilenameV(filename).toZIO

          // Step 2: Validate content type
          validatedContentType <- SecurityUtils.validateContentType(contentType).toZIO

          // Step 3: Validate file size
          validatedFileSize <- SecurityUtils.validateFileSizeV(fileSize).toZIO

          // Step 4: Validate path
          validatedPath <- SecurityUtils.validatePathV(s"uploads/${validatedFilename.value}").toZIO

        } yield assertTrue(
          validatedFilename.value == filename,
          validatedContentType == contentType,
          validatedFileSize.value == fileSize,
          validatedPath.value.toString.endsWith(filename)
        )
      },
      test("should reject malicious upload attempt") {
        val maliciousFilename    = "../../../etc/passwd"
        val maliciousContentType = "application/x-executable"
        val largeFileSize        = 200 * 1024 * 1024L // 200MB

        for {
          // All steps should fail
          filenameResult    <- SecurityUtils.validateFilenameV(maliciousFilename).toZIO.exit
          contentTypeResult <- SecurityUtils.validateContentType(maliciousContentType).toZIO.exit
          fileSizeResult    <- SecurityUtils.validateFileSizeV(largeFileSize).toZIO.exit
          pathResult        <- SecurityUtils.validatePathV(maliciousFilename).toZIO.exit

        } yield assertTrue(
          filenameResult.isFailure,
          contentTypeResult.isFailure,
          fileSizeResult.isFailure,
          pathResult.isFailure
        )
      },
      test("should handle edge cases gracefully") {
        for {
          // Null inputs
          nullFilename    <- SecurityUtils.validateFilenameV(null).toZIO.exit
          nullContentType <- SecurityUtils.validateContentType(null).toZIO.exit
          nullUrl         <- SecurityUtils.validateUrl(null).toZIO.exit

          // Empty inputs
          emptyFilename    <- SecurityUtils.validateFilenameV("").toZIO.exit
          emptyContentType <- SecurityUtils.validateContentType("").toZIO.exit
          emptyUrl         <- SecurityUtils.validateUrl("").toZIO.exit

          // Whitespace inputs
          whitespaceFilename    <- SecurityUtils.validateFilenameV("   ").toZIO.exit
          whitespaceContentType <- SecurityUtils.validateContentType("   ").toZIO.exit
          whitespaceUrl         <- SecurityUtils.validateUrl("   ").toZIO.exit
        } yield assertTrue(
          nullFilename.isFailure,
          nullContentType.isFailure,
          nullUrl.isFailure,
          emptyFilename.isFailure,
          emptyContentType.isFailure,
          emptyUrl.isFailure,
          whitespaceFilename.isFailure,
          whitespaceContentType.isFailure,
          whitespaceUrl.isFailure
        )
      }
    )
  )
}

// scalafix:on
