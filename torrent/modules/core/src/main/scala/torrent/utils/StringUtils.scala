package torrent
package utils

import java.nio.charset.StandardCharsets

import zio.*
import zio.stream.{ ZPipeline, ZSink }

object StringUtils:

  extension (str: String) def utf8Bytes: Either[String, Bytes] = Bytes(str.getBytes(StandardCharsets.UTF_8))

  /**
   * pattern to group consecutive chars by kind (alpha, numeric)
   * @return
   *   Pattern to turn aaa222vvv44 -> [aaa, 222, vvv, 44]
   */
  private[torrent] inline def groupCharKinds = "(?<=[A-Za-z])(?=\\d)|(?<=\\d)(?=[A-Za-z])".r.pattern

  private inline def sep: Bytes = "-".utf8Bytes.toOption.get

  def normalizeStream: ZSink[Any, Nothing, String, String, String] =
    ZPipeline
      .mapChunks((_: Chunk[String]).flatMap(string => Chunk.fromArray(groupCharKinds.split(string))))
      .intersperse("-") >>> ZSink.mkString

  val normalize: String => String = _.toLowerCase.trim
    .replaceAll("[^a-z0-9]", "")           // Remove all non-alphanumerics
    .replaceAll("([a-z]+)(\\d+)", "$1-$2") // Insert dash between letters and numbers
    .replaceAll("(\\d+)([a-z]+)", "$1-$2") // Insert dash between letters and numbers
