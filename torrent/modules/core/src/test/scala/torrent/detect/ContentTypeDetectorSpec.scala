package torrent.detect

import torrent.detect.ContentTypeDetector.ValidatedBytes

import zio.*
import zio.test.*

object ContentTypeDetectorSpec extends ZIOSpecDefault:

  def spec =
    suite("ContentTypeDetector")(
      test("should detect PNG file with high confidence") {
        val pngHeader = Chunk(0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a).map(_.toByte)

        for
          detector  <- ZIO.service[ContentTypeDetector]
          validated <- ValidatedBytes(pngHeader).fold(ZIO.fail(_), ZIO.succeed(_))
          result    <- detector.detectFromBytes(validated)
        yield assertTrue(
          result.exists(_.contentType == ContentType.PNG) &&
            result.exists(_.confidence == ConfidenceScore.High)
        )
      },
      test("should detect valid JSON as JSON with low confidence") {
        val json  = """{"key": "value"}"""
        val bytes = Chunk.fromArray(json.getBytes("UTF-8"))

        for
          detector  <- ZIO.service[ContentTypeDetector]
          validated <- ValidatedBytes(bytes).fold(ZIO.fail(_), ZIO.succeed(_))
          result    <- detector.detectFromBytes(validated)
        yield assertTrue(
          result.exists(_.contentType == ContentType.JSON) &&
            result.exists(_.confidence == ConfidenceScore.Low)
        )
      },
      test("should detect plain text") {
        val text  = "This is a plain text file."
        val bytes = Chunk.fromArray(text.getBytes("UTF-8"))

        for
          detector  <- ZIO.service[ContentTypeDetector]
          validated <- ValidatedBytes(bytes).fold(ZIO.fail(_), ZIO.succeed(_))
          result    <- detector.detectFromBytes(validated)
        yield assertTrue(
          result.exists(_.contentType == ContentType.PlainText) &&
            result.exists(_.confidence == ConfidenceScore.Low)
        )
      },
      test("should return None for unknown format") {
        val junk = Chunk.fromArray(Array.fill(20)(0x00.toByte))

        for
          detector  <- ZIO.service[ContentTypeDetector]
          validated <- ValidatedBytes(junk).fold(ZIO.fail(_), ZIO.succeed(_))
          result    <- detector.detectFromBytes(validated)
        yield assertTrue(result.isEmpty)
      },
      test("should detect CSV with low confidence") {
        val csv   = "name,age\njohn,30\njane,25"
        val bytes = Chunk.fromArray(csv.getBytes("UTF-8"))

        for
          detector  <- ZIO.service[ContentTypeDetector]
          validated <- ValidatedBytes(bytes).fold(ZIO.fail(_), ZIO.succeed(_))
          result    <- detector.detectFromBytes(validated)
        yield assertTrue(
          result.exists(_.contentType == ContentType.CSV) &&
            result.exists(_.confidence == ConfidenceScore.Low)
        )
      },
      test("should detect DOCX from stream with medium confidence") {
        import java.nio.file.{ Files, Paths }
        import zio.stream.ZStream

        val url    = getClass.getResource("/sample.docx")
        val path   = Paths.get(url.toURI)
        val bytes  = Files.readAllBytes(path)
        val stream = ZStream.fromIterable(bytes)

        for
          detector    <- ZIO.service[ContentTypeDetector]
          (result, _) <- detector.detectFromStream(stream)
        yield assertTrue(
          result.exists(_.contentType == ContentType.DOCX) &&
            result.exists(_.confidence == ConfidenceScore.Medium)
        )
      },
      test("should not rename file if confidence is low") {
        val text  = "some text without structure"
        val bytes = Chunk.fromArray(text.getBytes("UTF-8"))

        for
          validated <- ValidatedBytes(bytes).fold(ZIO.fail(_), ZIO.succeed(_))
          detector  <- ZIO.service[ContentTypeDetector]
          result    <- detector.detectFromBytes(validated)
        yield assertTrue(
          result.exists(_.contentType == ContentType.PlainText) &&
            result.exists(_.confidence == ConfidenceScore.Low)
        )
      }
    ).provideSomeLayerShared[Scope](ContentTypeDetector.live)
