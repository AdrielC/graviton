package graviton
package ingest

import graviton.*
import graviton.frame.*
import zio.*
import zio.stream.*

import core.model.BlockBuilder
import graviton.core.model.*

object IngestPipeline:

  private def truncatedHash(algo: HashAlgorithm, block: Block, bytes: Int): Chunk[Byte] =
    val (_, out) = Scan.hash(algo).runAll(BlockBuilder.chunkify(block.bytes))
    out.flatMap(b => b.bytes).take(bytes)

  /**
   * A pipeline that consumes raw bytes and emits encoded frames as byte chunks.
   * Strict one-block-per-frame semantics.
   */
  val pipeline: ZPipeline[Any, GravitonError, Block, Frame] =
    ZChannel
      .unwrapScoped {
        Ref.make[Option[FileSize]](Option.empty[FileSize]).map { (total: Ref[Option[FileSize]]) =>

          def loop(conf: IngestConfig): ZChannel[Any, Throwable, Chunk[Block], Any, Throwable, Chunk[Frame], Any] =

            def framePayload(block: Block): Frame =
              val hashId = Algorithms.HashIds.fromAlgo(conf.hashAlgorithm)
              val trunc  = truncatedHash(conf.hashAlgorithm, block, conf.framing.truncatedHashBytes)
              val header = FrameHeader(
                version = conf.framing.version,
                flags = 0,
                hashId = hashId,
                compId = Algorithms.CompressionIds.None,
                encId = Algorithms.EncryptionIds.None,
                truncHashLen = conf.framing.truncatedHashBytes.toByte,
                payloadLen = block.blockSize,
                nonce = None,
                keyId = conf.framing.keyId,
                aad = conf.framing.aad,
                truncHash = trunc,
              )

              FrameCodec.encode(header, block)

            end framePayload

            def guardSize(block: Block): ZIO[Any, GravitonError.PolicyViolation, Unit] =
              conf.maxBytes match
                case None        => ZIO.unit
                case Some(limit) =>
                  total.updateAndGet(_.fold(Some(block.fileSize))(s => s ++ block.fileSize)).flatMap { now =>
                    if now.exists(_ <= limit) then ZIO.unit
                    else ZIO.fail(GravitonError.PolicyViolation("ingest size exceeds limit: " + limit))
                  }
            end guardSize

            ZChannel.readWith(
              (in: Chunk[Block]) =>
                ZChannel.unwrap(ZIO.foldLeft(in)(ZChannel.unit: ZChannel[Any, Throwable, Chunk[Block], Any, Throwable, Chunk[Frame], Any]) {
                  (acc, block) =>
                    (guardSize(block)).flatMap { _ =>
                      ZIO.succeed {
                        val frame = framePayload(block)
                        if frame.payload.isEmpty then ZChannel.unit
                        else acc *> ZChannel.write(Chunk.single(frame)) *> loop(conf)
                      }

                    }
                }),
              (err: Throwable) => ZChannel.fail(err),
              (_: Any) => ZChannel.unit,
            )

          end loop

          ZChannel.fromZIO(IngestConfig.fiberRef.get).flatMap(conf => loop(conf))
        }
      }
      .toPipeline
      .mapError(e => GravitonError.BackendUnavailable(Option(e.getMessage).getOrElse(e.toString)))
