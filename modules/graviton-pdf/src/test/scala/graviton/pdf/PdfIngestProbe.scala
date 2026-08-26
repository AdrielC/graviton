package graviton.pdf

import graviton.runtime.Graviton
import zio.*
import zio.pdf.PdfMime
import zio.stream.ZStream

import java.nio.file.{Files, Paths}
import java.security.MessageDigest
import java.util.HexFormat

/**
 * Runs a real PDF through the filesystem CAS without materializing the file.
 *
 * Invoke through `scripts/probe-pdf-ingest.sh path/to/document.pdf`.
 */
object PdfIngestProbe extends ZIOAppDefault:

  private def digest(stream: ZStream[Any, Throwable, Byte]): Task[(Long, String)] =
    for
      messageDigest <- ZIO.attempt(MessageDigest.getInstance("SHA-256"))
      byteCount     <- stream.mapChunksZIO { chunk =>
                         ZIO.attempt {
                           chunk.foreach(byte => messageDigest.update(byte))
                           chunk
                         }
                       }.runCount
      hex           <- ZIO.attempt(HexFormat.of().formatHex(messageDigest.digest()))
    yield byteCount -> hex

  override def run: ZIO[ZIOAppArgs, Throwable, Unit] =
    for
      args           <- ZIOAppArgs.getArgs
      input          <- ZIO
                          .fromOption(args.headOption)
                          .map(Paths.get(_))
                          .orElseFail(new IllegalArgumentException("Expected a PDF path"))
      storageRoot    <- ZIO
                          .fromOption(args.drop(1).headOption)
                          .map(Paths.get(_))
                          .orElseFail(new IllegalArgumentException("Expected a filesystem CAS directory"))
      _              <- ZIO
                          .fail(new IllegalArgumentException(s"PDF does not exist: $input"))
                          .unlessZIO(
                            ZIO.attemptBlocking(Files.isRegularFile(input))
                          )
      expectedBytes  <- ZIO.attemptBlocking(Files.size(input))
      expectedDigest <- digest(ZStream.fromFile(input.toFile, chunkSize = 64 * 1024))
      graviton       <- Graviton.fs(storageRoot)
      result         <- PdfIngest.put(
                          graviton.blobStore,
                          PdfMime.mimeType,
                          ZStream.fromFile(input.toFile, chunkSize = 64 * 1024),
                        )
      restoredDigest <- digest(graviton.stream(result.key))
      verified       <- graviton.verify(result.key)
      _              <- ZIO
                          .fail(
                            new IllegalStateException(
                              s"Filesystem CAS round trip failed: source=$expectedDigest restored=$restoredDigest verified=$verified"
                            )
                          )
                          .unless(
                            expectedDigest == restoredDigest &&
                              expectedBytes == result.stats.totalBytes &&
                              verified
                          )
      _              <- Console.printLine(
                          s"pdf-ingest-proof key=${result.key.bits.render} bytes=${result.stats.totalBytes} blocks=${result.stats.blockCount} " +
                            s"fresh=${result.stats.freshBlocks} sha256=${restoredDigest._2} verified=$verified"
                        )
    yield ()

end PdfIngestProbe
