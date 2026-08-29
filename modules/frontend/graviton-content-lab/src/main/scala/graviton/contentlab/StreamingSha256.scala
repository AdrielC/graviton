package graviton.contentlab

import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport
import scala.scalajs.js.typedarray.Uint8Array
import zio.Chunk
import zio.stream.{ZSink, ZStream}
import zio.ZIO

/** Incremental SHA-256 for browser streams with 64 KiB input rechunking. */
private[contentlab] object StreamingSha256:
  def digest[R](source: ZStream[R, Throwable, Byte]): ZIO[R, Throwable, Chunk[Byte]] =
    val initial = NobleSha256.create()
    source
      .rechunk(BrowserFileAnalysis.HashWindowBytes)
      .run(
        ZSink.foldLeftChunks(initial) { (state, bytes: Chunk[Byte]) =>
          val _ = state.update(toUint8Array(bytes))
          state
        }
      )
      .map(state => fromUint8Array(state.digest()))

  private def toUint8Array(bytes: Chunk[Byte]): Uint8Array =
    val result = new Uint8Array(bytes.length)
    var index  = 0
    while index < bytes.length do
      result(index) = (bytes(index).toInt & 0xff).toShort
      index += 1
    result

  private def fromUint8Array(bytes: Uint8Array): Chunk[Byte] =
    val builder = Chunk.newBuilder[Byte]
    var index   = 0
    while index < bytes.length do
      builder += bytes(index).toByte
      index += 1
    builder.result()

@js.native
@JSImport("@noble/hashes/sha2.js", "sha256")
private object NobleSha256 extends js.Object:
  def create(): NobleSha256State = js.native

@js.native
private trait NobleSha256State extends js.Object:
  def update(input: Uint8Array): NobleSha256State = js.native
  def digest(): Uint8Array                        = js.native
