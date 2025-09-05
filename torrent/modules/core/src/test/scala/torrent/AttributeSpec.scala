package torrent

import java.time.Instant

import zio.*
import zio.test.*

object AttributeSpec extends ZIOSpecDefault:

  def spec = suite("Attribute")(
    test("type-safe heterogeneous map operations") {
      val now      = Instant.now()
      val keywords = List("document", "pdf", "important")

      // Build attribute map with type safety
      val attributes = Attribute.AttributeMap.builder
        .add(Attribute.FileName, "document.pdf")
        .add(Attribute.Size, 1024L)
        .add(Attribute.Created, now)
        .add(Attribute.Keywords, keywords)
        .add(Attribute.PageCount, 42)
        .build

      for
        // Type-safe retrieval
        filename    <- ZIO.fromOption(attributes.get(Attribute.FileName))
        size        <- ZIO.fromOption(attributes.get(Attribute.Size))
        created     <- ZIO.fromOption(attributes.get(Attribute.Created))
        keywordList <- ZIO.fromOption(attributes.get(Attribute.Keywords))
        pageCount   <- ZIO.fromOption(attributes.get(Attribute.PageCount))

        // Verify types are preserved
        _ <- assertTrue(filename == "document.pdf")
        _ <- assertTrue(size == 1024L)
        _ <- assertTrue(created == now)
        _ <- assertTrue(keywordList == keywords)
        _ <- assertTrue(pageCount == 42)

        // Test missing attribute
        missing = attributes.get(Attribute.Author)
        _      <- assertTrue(missing.isEmpty)
      yield assertCompletes
    },
    test("attribute map updates and operations") {
      val initial = Attribute.AttributeMap.empty
        .set(Attribute.FileName, "test.txt")
        .set(Attribute.Size, 100L)

      val updated = initial
        .set(Attribute.ContentType, "text/plain")
        .update(Attribute.Size)(_ * 2)
        .remove(Attribute.FileName)

      for
        // Check updates
        contentType <- ZIO.fromOption(updated.get(Attribute.ContentType))
        size        <- ZIO.fromOption(updated.get(Attribute.Size))

        _ <- assertTrue(contentType == "text/plain")
        _ <- assertTrue(size == 200L)
        _ <- assertTrue(!updated.contains(Attribute.FileName))
        _ <- assertTrue(updated.size == 2)
      yield assertCompletes
    },
    test("hint creation and usage") {
      val hint = Hint
        .filename("document.pdf")
        .withAttribute(Attribute.Size, 1024L)
        .withAttribute(Attribute.ContentType, "application/pdf")

      for
        filename    <- ZIO.fromOption(hint.get(Attribute.FileName))
        size        <- ZIO.fromOption(hint.get(Attribute.Size))
        contentType <- ZIO.fromOption(hint.get(Attribute.ContentType))

        _ <- assertTrue(filename == "document.pdf")
        _ <- assertTrue(size == 1024L)
        _ <- assertTrue(contentType == "application/pdf")
      yield assertCompletes
    },
    test("dynamic value round-trip") {
      val original  = "test-file.pdf"
      val dynamic   = Attribute.FileName.toDynamic(original)
      val extracted = Attribute.FileName.extract(dynamic)

      assertTrue(extracted == Right(original))
    },
    test("type safety prevents wrong type extraction") {
      // Create a dynamic value for a string
      val stringDynamic = Attribute.FileName.toDynamic("test.txt")

      // Try to extract it as a Long (should fail)
      val wrongExtraction = Attribute.Size.extract(stringDynamic)

      assertTrue(wrongExtraction.isLeft)
    },
    test("attribute map merging") {
      val map1 = Attribute.AttributeMap.empty
        .set(Attribute.FileName, "file1.txt")
        .set(Attribute.Size, 100L)

      val map2 = Attribute.AttributeMap.empty
        .set(Attribute.ContentType, "text/plain")
        .set(Attribute.Size, 200L) // This should override

      val merged = map1 ++ map2

      for
        filename    <- ZIO.fromOption(merged.get(Attribute.FileName))
        size        <- ZIO.fromOption(merged.get(Attribute.Size))
        contentType <- ZIO.fromOption(merged.get(Attribute.ContentType))

        _ <- assertTrue(filename == "file1.txt")
        _ <- assertTrue(size == 200L) // Should be overridden value
        _ <- assertTrue(contentType == "text/plain")
        _ <- assertTrue(merged.size == 3)
      yield assertCompletes
    }
  )
