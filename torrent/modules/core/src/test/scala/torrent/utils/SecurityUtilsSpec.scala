package torrent.utils

import torrent.utils.SecurityUtils

import zio.*
import zio.test.*

// scalafix:off

object SecurityUtilsSpec extends ZIOSpecDefault {

  def spec = suite("SecurityUtils")(
    suite("validatePath")(
      test("should accept valid paths") {
        for {
          result  <- SecurityUtils.validatePathV("test.txt").toZIO
          result2 <- SecurityUtils.validatePathV("folder/subfolder/file.pdf").toZIO
          result3 <- SecurityUtils.validatePathV("C:/Users/username/Documents/file.txt").toZIO
          result4 <- SecurityUtils.validatePathV("C:\\Users\\username\\Documents\\file.txt").toZIO
          result5 <- SecurityUtils.validatePathV("C:\\Users\\username\\Desktop\\report.pdf").toZIO
          result6 <- SecurityUtils.validatePathV("C:\\Users\\username\\Downloads\\image.jpg").toZIO
        } yield assertTrue(
          result.value.toString.endsWith("test.txt"),
          result2.value.toString.endsWith("file.pdf"),
          result3.value.toString.endsWith("file.txt"),
          result4.value.toString.endsWith("file.txt"),
          result5.value.toString.endsWith("report.pdf"),
          result6.value.toString.endsWith("image.jpg")
        )
      },
      test("should reject path traversal attempts") {
        for {
          result1 <- SecurityUtils.validatePathV("../../../etc/passwd").toZIO.exit
          result2 <- SecurityUtils.validatePathV("..\\..\\..\\Windows\\System32\\config\\SAM").toZIO.exit
          result3 <- SecurityUtils.validatePathV("~/ssh/id_rsa").toZIO.exit
          result4 <- SecurityUtils.validatePathV("/etc/shadow").toZIO.exit
          result5 <- SecurityUtils.validatePathV("/var/log/auth.log").toZIO.exit
          result6 <- SecurityUtils.validatePathV("/proc/1/environ").toZIO.exit
          result7 <- SecurityUtils.validatePathV("/sys/kernel/debug").toZIO.exit
        } yield assertTrue(
          result1.isFailure,
          result2.isFailure,
          result3.isFailure,
          result4.isFailure,
          result5.isFailure,
          result6.isFailure,
          result7.isFailure
        )
      },
      test("should reject dangerous Windows system paths") {
        for {
          result1 <- SecurityUtils.validatePathV("C:\\Windows\\System32\\config\\SAM").toZIO.exit
          result2 <- SecurityUtils.validatePathV("C:\\Program Files\\malicious.exe").toZIO.exit
          result3 <- SecurityUtils.validatePathV("C:\\Users\\Administrator\\AppData\\System32\\evil.dll").toZIO.exit
        } yield assertTrue(
          result1.isFailure,
          result2.isFailure,
          result3.isFailure
        )
      },
      test("should normalize paths correctly") {
        for {
          result <- SecurityUtils.validatePathV("./folder/../file.txt").toZIO
        } yield assertTrue(result.value.toString.endsWith("file.txt"))
      }
    ),
    suite("validateFilename")(
      test("should accept valid filenames") {
        for {
          result1 <- SecurityUtils.validateFilenameV("document.pdf").toZIO
          result2 <- SecurityUtils.validateFilenameV("my-file_123.txt").toZIO
          result3 <- SecurityUtils.validateFilenameV("image.jpg").toZIO
        } yield assertTrue(
          result1.value == "document.pdf",
          result2.value == "my-file_123.txt",
          result3.value == "image.jpg"
        )
      },
      test("should reject dangerous filenames") {
        for {
          result1 <- SecurityUtils.validateFilenameV("../../../malicious.exe").toZIO.exit
          result2 <- SecurityUtils.validateFilenameV("file<script>alert('xss')</script>.txt").toZIO.exit
          result3 <- SecurityUtils.validateFilenameV("CON").toZIO.exit
          result4 <- SecurityUtils.validateFilenameV(".hidden").toZIO.exit
          result5 <- SecurityUtils.validateFilenameV("file|with|pipes.txt").toZIO.exit
          result6 <- SecurityUtils.validateFilenameV("file:with:colons.txt").toZIO.exit
          result7 <- SecurityUtils.validateFilenameV("file\"with\"quotes.txt").toZIO.exit
          result8 <- SecurityUtils.validateFilenameV("file<with>tags.txt").toZIO.exit
        } yield assertTrue(
          result1.isFailure,
          result2.isFailure,
          result3.isFailure,
          result4.isFailure,
          result5.isFailure,
          result6.isFailure,
          result7.isFailure,
          result8.isFailure
        )
      },
      test("should reject null or empty filenames") {
        for {
          result1 <- SecurityUtils.validateFilenameV(null).toZIO.exit
          result2 <- SecurityUtils.validateFilenameV("").toZIO.exit
          result3 <- SecurityUtils.validateFilenameV("   ").toZIO.exit
        } yield assertTrue(
          result1.isFailure,
          result2.isFailure,
          result3.isFailure
        )
      },
      test("should reject filenames that are too long") {
        val longName = "a" * 256
        for {
          result <- SecurityUtils.validateFilenameV(longName).toZIO.exit
        } yield assertTrue(result.isFailure)
      },
      test("should trim whitespace") {
        for {
          result <- SecurityUtils.validateFilenameV("  file.txt  ").toZIO
        } yield assertTrue(result.value == "file.txt")
      },
      test("should preserve quotes in input sanitization") {
        val input  = """Hello "World" with 'quotes'"""
        val result = SecurityUtils.sanitizeInput(input)
        assertTrue(result == """Hello "World" with 'quotes'""")
      },
      test("should reject Windows reserved filename 'CON'") {
        for {
          result <- SecurityUtils.validateFilenameV("CON").toZIO.exit
        } yield assertTrue(result.isFailure)
      },
      test("should reject Windows reserved filename 'con' (case insensitive)") {
        for {
          result <- SecurityUtils.validateFilenameV("con").toZIO.exit
        } yield assertTrue(result.isFailure)
      }
    ),
    suite("sanitizeInput")(
      test("should remove HTML/XML tags") {
        val input  = "<script>alert('xss')</script>"
        val result = SecurityUtils.sanitizeInput(input)
        assertTrue(result == "scriptalert('xss')/script")
      },
      test("should remove dangerous protocols") {
        val input1  = "javascript:alert('xss')"
        val input2  = "data:text/html,<script>alert('xss')</script>"
        val result1 = SecurityUtils.sanitizeInput(input1)
        val result2 = SecurityUtils.sanitizeInput(input2)
        assertTrue(
          result1 == "alert('xss')",
          result2 == "text/html,scriptalert('xss')/script"
        )
      },
      test("should handle null input") {
        val result = SecurityUtils.sanitizeInput(null)
        assertTrue(result == "")
      },
      test("should trim whitespace") {
        val result = SecurityUtils.sanitizeInput("  hello world  ")
        assertTrue(result == "hello world")
      },
      test("should preserve safe content") {
        val input  = "Hello, this is a safe document.pdf"
        val result = SecurityUtils.sanitizeInput(input)
        assertTrue(result == "Hello, this is a safe document.pdf")
      }
    ),
    suite("validateFileSize")(
      test("should accept valid file sizes") {
        for {
          result1 <- SecurityUtils.validateFileSizeV(1024L).toZIO
          result2 <- SecurityUtils.validateFileSizeV(50 * 1024 * 1024L).toZIO
          result3 <- SecurityUtils.validateFileSizeV(100 * 1024 * 1024L).toZIO
        } yield assertTrue(
          result1.value == 1024L,
          result2.value == 50 * 1024 * 1024L,
          result3.value == 100 * 1024 * 1024L
        )
      },
      test("should reject negative file sizes") {
        for {
          result <- SecurityUtils.validateFileSizeV(-1L).toZIO.exit
        } yield assertTrue(result.isFailure)
      },
      test("should reject files that are too large") {
        for {
          result <- SecurityUtils.validateFileSizeV(200 * 1024 * 1024L).toZIO.exit
        } yield assertTrue(result.isFailure)
      },
      test("should accept custom size limits") {
        val customLimit = 50 * 1024 * 1024L // 50MB
        for {
          result <- SecurityUtils.validateFileSizeV(25 * 1024 * 1024L, min = 0, max = customLimit).toZIO
        } yield assertTrue(result.value == 25 * 1024 * 1024L)
      },
      test("should reject files exceeding custom limits") {
        val customLimit = 50 * 1024 * 1024L // 50MB
        for {
          result <- SecurityUtils.validateFileSizeV(75 * 1024 * 1024L, min = 0, max = customLimit).toZIO.exit
        } yield assertTrue(result.isFailure)
      }
    ),
    suite("validateContentType")(
      test("should accept safe content types") {
        for {
          result1 <- SecurityUtils.validateContentType("application/pdf").toZIO
          result2 <- SecurityUtils.validateContentType("image/jpeg").toZIO
          result3 <- SecurityUtils.validateContentType("text/plain").toZIO
          result4 <- SecurityUtils.validateContentType("application/json").toZIO
        } yield assertTrue(
          result1 == "application/pdf",
          result2 == "image/jpeg",
          result3 == "text/plain",
          result4 == "application/json"
        )
      },
      test("should reject dangerous content types") {
        for {
          result1 <- SecurityUtils.validateContentType("application/x-executable").toZIO.exit
          result2 <- SecurityUtils.validateContentType("application/x-msdownload").toZIO.exit
          result3 <- SecurityUtils.validateContentType("application/x-msi").toZIO.exit
          result4 <- SecurityUtils.validateContentType("application/x-msdos-program").toZIO.exit
        } yield assertTrue(
          result1.isFailure,
          result2.isFailure,
          result3.isFailure,
          result4.isFailure
        )
      },
      test("should reject null or empty content types") {
        for {
          result1 <- SecurityUtils.validateContentType(null).toZIO.exit
          result2 <- SecurityUtils.validateContentType("").toZIO.exit
          result3 <- SecurityUtils.validateContentType("   ").toZIO.exit
        } yield assertTrue(
          result1.isFailure,
          result2.isFailure,
          result3.isFailure
        )
      },
      test("should normalize content types to lowercase") {
        for {
          result <- SecurityUtils.validateContentType("APPLICATION/PDF").toZIO
        } yield assertTrue(result == "application/pdf")
      },
      test("should trim whitespace") {
        for {
          result <- SecurityUtils.validateContentType("  application/pdf  ").toZIO
        } yield assertTrue(result == "application/pdf")
      }
    ),
    suite("validateUrl")(
      test("should accept safe URLs") {
        for {
          result1 <- SecurityUtils.validateUrl("https://example.com").toZIO
          result2 <- SecurityUtils.validateUrl("http://api.github.com").toZIO
          result3 <- SecurityUtils.validateUrl("https://docs.scala-lang.org").toZIO
        } yield assertTrue(
          result1 == "https://example.com",
          result2 == "http://api.github.com",
          result3 == "https://docs.scala-lang.org"
        )
      },
      test("should reject dangerous protocols") {
        for {
          result1 <- SecurityUtils.validateUrl("file:///etc/passwd").toZIO.exit
          result2 <- SecurityUtils.validateUrl("ftp://malicious.com").toZIO.exit
          result3 <- SecurityUtils.validateUrl("gopher://malicious.com").toZIO.exit
          result4 <- SecurityUtils.validateUrl("dict://malicious.com").toZIO.exit
          result5 <- SecurityUtils.validateUrl("ldap://malicious.com").toZIO.exit
        } yield assertTrue(
          result1.isFailure,
          result2.isFailure,
          result3.isFailure,
          result4.isFailure,
          result5.isFailure
        )
      },
      test("should reject localhost and private IP addresses") {
        for {
          result1 <- SecurityUtils.validateUrl("http://localhost:8080").toZIO.exit
          result2 <- SecurityUtils.validateUrl("http://127.0.0.1:3000").toZIO.exit
          result3 <- SecurityUtils.validateUrl("http://::1:8080").toZIO.exit
          result4 <- SecurityUtils.validateUrl("http://192.168.1.1").toZIO.exit
          result5 <- SecurityUtils.validateUrl("http://10.0.0.1").toZIO.exit
          result6 <- SecurityUtils.validateUrl("http://172.16.0.1").toZIO.exit
        } yield assertTrue(
          result1.isFailure,
          result2.isFailure,
          result3.isFailure,
          result4.isFailure,
          result5.isFailure,
          result6.isFailure
        )
      },
      test("should reject null or empty URLs") {
        for {
          result1 <- SecurityUtils.validateUrl(null).toZIO.exit
          result2 <- SecurityUtils.validateUrl("").toZIO.exit
          result3 <- SecurityUtils.validateUrl("   ").toZIO.exit
        } yield assertTrue(
          result1.isFailure,
          result2.isFailure,
          result3.isFailure
        )
      },
      test("should normalize URLs to lowercase") {
        for {
          result <- SecurityUtils.validateUrl("HTTPS://EXAMPLE.COM").toZIO
        } yield assertTrue(result == "https://example.com")
      },
      test("should trim whitespace") {
        for {
          result <- SecurityUtils.validateUrl("  https://example.com  ").toZIO
        } yield assertTrue(result == "https://example.com")
      }
    )
  )
}

// scalafix:on
