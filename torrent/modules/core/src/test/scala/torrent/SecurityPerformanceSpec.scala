package torrent

import torrent.utils.SecurityUtils

import zio.*
import zio.test.*

object SecurityPerformanceSpec extends ZIOSpecDefault {

  def spec = suite("Security Performance Tests")(
    suite("Filename Validation Performance")(
      test("should validate 1000 filenames quickly") {
        val filenames = (1 to 1000).map(i => s"file_$i.txt").toList

        for {
          startTime <- Clock.nanoTime
          results   <- ZIO.foreach(filenames) { filename =>
                         SecurityUtils.validateFilenameV(filename).toZIO
                       }
          endTime   <- Clock.nanoTime
          duration   = endTime - startTime
        } yield assertTrue(
          results.length == 1000,
          duration < 1_000_000_000L // Should complete in less than 1 second (nanoseconds)
        )
      },
      test("should handle mixed valid/invalid filenames efficiently") {
        val validNames   = (1 to 500).map(i => s"file_$i.txt").toList
        val invalidNames = (1 to 500).map(i => s"../../../malicious_$i.exe").toList
        val allNames     = validNames ++ invalidNames

        for {
          startTime <- Clock.nanoTime
          results   <- ZIO.foreach(allNames) { filename =>
                         SecurityUtils.validateFilenameV(filename).toZIO.exit
                       }
          endTime   <- Clock.nanoTime
          duration   = endTime - startTime

          validResults   = results.take(500).filter(_.isSuccess)
          invalidResults = results.drop(500).filter(_.isFailure)
        } yield assertTrue(
          validResults.length == 500,
          invalidResults.length == 500,
          duration < 1_000_000_000L // Should complete in less than 1 second (nanoseconds)
        )
      }
    ),
    suite("Path Validation Performance")(
      test("should validate 1000 paths quickly") {
        val paths = (1 to 1000).map(i => s"folder/subfolder/file_$i.txt").toList

        for {
          startTime <- Clock.nanoTime
          results   <- ZIO.foreach(paths) { path =>
                         SecurityUtils.validatePathV(path).toZIO
                       }
          endTime   <- Clock.nanoTime
          duration   = endTime - startTime
        } yield assertTrue(
          results.length == 1000,
          duration < 1_000_000_000L // Should complete in less than 1 second (nanoseconds)
        )
      },
      test("should handle path traversal detection efficiently") {
        val traversalPaths = (1 to 1000).map(i => s"../../../malicious_$i").toList

        for {
          startTime <- Clock.nanoTime
          results   <- ZIO.foreach(traversalPaths) { path =>
                         SecurityUtils.validatePathV(path).toZIO.exit
                       }
          endTime   <- Clock.nanoTime
          duration   = endTime - startTime

          failedResults = results.filter(_.isFailure)
        } yield assertTrue(
          failedResults.length == 1000,
          duration < 1_000_000_000L // Should complete in less than 1 second (nanoseconds)
        )
      }
    ),
    suite("Content Type Validation Performance")(
      test("should validate 1000 content types quickly") {
        val contentTypes  = List(
          "application/pdf",
          "image/jpeg",
          "text/plain",
          "application/json",
          "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        )
        val repeatedTypes = (1 to 200).flatMap(_ => contentTypes).toList

        for {
          startTime <- Clock.nanoTime
          results   <- ZIO.foreach(repeatedTypes) { contentType =>
                         SecurityUtils.validateContentType(contentType).toZIO
                       }
          endTime   <- Clock.nanoTime
          duration   = endTime - startTime
        } yield assertTrue(
          results.length == 1000,
          duration < 1_000_000_000L // Should complete in less than 1 second (nanoseconds)
        )
      },
      test("should handle dangerous content type detection efficiently") {
        val dangerousTypes    = List(
          "application/x-executable",
          "application/x-msdownload",
          "application/x-msi",
          "application/x-msdos-program"
        )
        val repeatedDangerous = (1 to 250).flatMap(_ => dangerousTypes).toList

        for {
          startTime <- Clock.nanoTime
          results   <- ZIO.foreach(repeatedDangerous) { contentType =>
                         SecurityUtils.validateContentType(contentType).toZIO.exit
                       }
          endTime   <- Clock.nanoTime
          duration   = endTime - startTime

          failedResults = results.filter(_.isFailure)
        } yield assertTrue(
          failedResults.length == 1000,
          duration < 1_000_000_000L // Should complete in less than 1 second (nanoseconds)
        )
      }
    ),
    suite("Input Sanitization Performance")(
      test("should sanitize 1000 inputs quickly") {
        val inputs = (1 to 1000).map(i => s"<script>alert('xss_$i')</script>").toList

        for {
          startTime <- Clock.nanoTime
          results   <- ZIO.foreach(inputs) { input =>
                         ZIO.succeed(SecurityUtils.sanitizeInput(input))
                       }
          endTime   <- Clock.nanoTime
          duration   = endTime - startTime
        } yield assertTrue(
          results.length == 1000,
          results.forall(_.contains("script")),
          results.forall(!_.contains("<script>")),
          duration < 1_000_000_000L // Should complete in less than 1 second (nanoseconds)
        )
      },
      test("should handle mixed safe/malicious inputs efficiently") {
        val safeInputs      = (1 to 500).map(i => s"Safe document $i").toList
        val maliciousInputs = (1 to 500).map(i => s"javascript:alert('xss_$i')").toList
        val allInputs       = safeInputs ++ maliciousInputs

        for {
          startTime <- Clock.nanoTime
          results   <- ZIO.foreach(allInputs) { input =>
                         ZIO.succeed(SecurityUtils.sanitizeInput(input))
                       }
          endTime   <- Clock.nanoTime
          duration   = endTime - startTime

          safeResults      = results.take(500)
          sanitizedResults = results.drop(500)
        } yield assertTrue(
          safeResults == safeInputs,
          sanitizedResults.forall(!_.startsWith("javascript:")),
          duration < 1_000_000_000L // Should complete in less than 1 second (nanoseconds)
        )
      }
    ),
    suite("URL Validation Performance")(
      test("should validate 1000 URLs quickly") {
        val urls = (1 to 1000).map(i => s"https://example$i.com").toList

        for {
          startTime <- Clock.nanoTime
          results   <- ZIO.foreach(urls) { url =>
                         SecurityUtils.validateUrl(url).toZIO
                       }
          endTime   <- Clock.nanoTime
          duration   = endTime - startTime
        } yield assertTrue(
          results.length == 1000,
          duration < 1_000_000_000L // Should complete in less than 1 second (nanoseconds)
        )
      },
      test("should handle dangerous URL detection efficiently") {
        val dangerousUrls     = List(
          "file:///etc/passwd",
          "ftp://malicious.com",
          "http://localhost:8080",
          "http://127.0.0.1:3000"
        )
        val repeatedDangerous = (1 to 250).flatMap(_ => dangerousUrls).toList

        for {
          startTime <- Clock.nanoTime
          results   <- ZIO.foreach(repeatedDangerous) { url =>
                         SecurityUtils.validateUrl(url).toZIO.exit
                       }
          endTime   <- Clock.nanoTime
          duration   = endTime - startTime

          failedResults = results.filter(_.isFailure)
        } yield assertTrue(
          failedResults.length == 1000,
          duration < 1_000_000_000L // Should complete in less than 1 second (nanoseconds)
        )
      }
    ),
    suite("File Size Validation Performance")(
      test("should validate 1000 file sizes quickly") {
        val fileSizes = (1 to 1000).map(i => i * 1024L).toList // 1KB to 1MB

        for {
          startTime <- Clock.nanoTime
          results   <- ZIO.foreach(fileSizes) { size =>
                         SecurityUtils.validateFileSizeV(size).toZIO
                       }
          endTime   <- Clock.nanoTime
          duration   = endTime - startTime
        } yield assertTrue(
          results.length == 1000,
          duration < 1_000_000_000L // Should complete in less than 1 second (nanoseconds)
        )
      },
      test("should handle large file size rejection efficiently") {
        val largeSizes = (1 to 1000).map(i => (200 + i) * 1024 * 1024L).toList // 200MB+

        for {
          startTime <- Clock.nanoTime
          results   <- ZIO.foreach(largeSizes) { size =>
                         SecurityUtils.validateFileSizeV(size).toZIO.exit
                       }
          endTime   <- Clock.nanoTime
          duration   = endTime - startTime

          failedResults = results.filter(_.isFailure)
        } yield assertTrue(
          failedResults.length == 1000,
          duration < 1_000_000_000L // Should complete in less than 1 second (nanoseconds)
        )
      }
    ),
    suite("End-to-End Security Workflow Performance")(
      test("should process complete security validation workflow efficiently") {
        val testCases = (1 to 100).map { i =>
          (
            s"document_$i.pdf",
            "application/pdf",
            1024 * 1024L, // 1MB
            s"uploads/document_$i.pdf"
          )
        }.toList

        for {
          startTime <- Clock.nanoTime
          results   <- ZIO.foreach(testCases) { case (filename, contentType, fileSize, path) =>
                         for {
                           validatedFilename    <- SecurityUtils.validateFilenameV(filename).toZIO
                           validatedContentType <- SecurityUtils.validateContentType(contentType).toZIO
                           validatedFileSize    <- SecurityUtils.validateFileSizeV(fileSize).toZIO
                           validatedPath        <- SecurityUtils.validatePathV(path).toZIO
                         } yield (validatedFilename, validatedContentType, validatedFileSize, validatedPath)
                       }
          endTime   <- Clock.nanoTime
          duration   = endTime - startTime
        } yield assertTrue(
          results.length == 100,
          duration < 2_000_000_000L // Should complete in less than 2 seconds (nanoseconds)
        )
      },
      test("should reject malicious workflows efficiently") {
        val maliciousCases = (1 to 100).map { i =>
          (
            s"../../../malicious_$i.exe",
            "application/x-executable",
            200 * 1024 * 1024L, // 200MB
            s"../../../malicious_$i.exe"
          )
        }.toList

        for {
          startTime <- Clock.nanoTime
          results   <- ZIO.foreach(maliciousCases) { case (filename, contentType, fileSize, path) =>
                         for {
                           filenameResult    <- SecurityUtils.validateFilenameV(filename).toZIO.exit
                           contentTypeResult <- SecurityUtils.validateContentType(contentType).toZIO.exit
                           fileSizeResult    <- SecurityUtils.validateFileSizeV(fileSize).toZIO.exit
                           pathResult        <- SecurityUtils.validatePathV(path).toZIO.exit
                         } yield (filenameResult, contentTypeResult, fileSizeResult, pathResult)
                       }
          endTime   <- Clock.nanoTime
          duration   = endTime - startTime

          allFailed = results.forall { case (f, c, s, p) =>
                        f.isFailure && c.isFailure && s.isFailure && p.isFailure
                      }
        } yield assertTrue(
          results.length == 100,
          allFailed,
          duration < 2_000_000_000L // Should complete in less than 2 seconds (nanoseconds)
        )
      }
    )
  )
}
