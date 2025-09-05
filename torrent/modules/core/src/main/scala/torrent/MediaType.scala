package torrent

import scala.compiletime.constValue
import scala.compiletime.ops.string.*

import io.github.iltotore.iron.constraint.string.Match
import io.github.iltotore.iron.{ :|, DescribedAs }
import torrent.schemas.RefinedTypeExt

import zio.schema.Schema

/** Represents a MIME type */
type MediaType           = MediaType.T
type MediaTypeConstraint = DescribedAs[Match[MediaType.regex.type], "Must be a valid media (MIME) type"]
object MediaType extends RefinedTypeExt[String, MediaTypeConstraint]:

  inline val regex =
    """^[a-zA-Z][a-zA-Z0-9][a-zA-Z0-9!#$&\-\^_]*\/[a-zA-Z0-9][a-zA-Z0-9!#$&\-\^_.]*(?:\s*;\s*[a-zA-Z0-9!#$&\-\^_]+=(?:[a-zA-Z0-9!#$&\-\^_.]+|"[^"]*"))*$"""

  extension (value: MediaType)
    def mainType: String                = value.split("/").headOption.getOrElse("")
    def subType: String                 = value.split("/").drop(1).headOption.map(_.takeWhile(_ != ';')).getOrElse("")
    def parameters: Map[String, String] =
      value
        .split(";")
        .drop(1)
        .flatMap { param =>
          param.split("=") match
            case Array(k, v) => Some(k.trim -> v.trim)
            case _           => None
        }
        .toMap

    def fullType: String        = s"$mainType/$subType"
    def isBinary: Boolean       = binaryTypes.contains(fullType)
    def isCompressible: Boolean = compressibleTypes.contains(fullType)

    def isProbablyCompressible: Boolean =
      mainType == "text" ||
        fullType.startsWith("application/") && (
          subType.contains("json") ||
            subType.contains("xml") ||
            subType.contains("yaml") ||
            subType.contains("toml") ||
            subType.contains("csv") ||
            subType.contains("javascript") ||
            subType.contains("xhtml") ||
            subType.contains("ld+json")
        )

    infix def is[S <: Subtype[?]](subtype: S): Boolean = mainType == subtype.prefix
    def isImage: Boolean                               = value.is(subtype = image)
    def isPdf: Boolean                                 = fullType == application.pdf
    def isText: Boolean                                = value.is(text)
    def isAudio: Boolean                               = value.is(audio)
    def isVideo: Boolean                               = value.is(video)
    def isApplication: Boolean                         = value.is(application)
    def isMultipart: Boolean                           = value.is(multipart)

  trait Subtype[Prefix <: String](using val v: ValueOf[Prefix]):

    def prefix: Prefix = v.value

    type Apply[Suffix <: String] =
      (Prefix + "/" + Suffix) & MediaType :| DescribedAs[Match[regex.type], "Must be a valid media (MIME) type"]

    final transparent inline def make[Suffix <: String]: MediaType = applyUnsafe(constValue[Apply[Suffix] & MediaType])

  end Subtype

  object image extends Subtype["image"]:
    transparent inline def jpeg: MediaType = make["jpeg"]
    transparent inline def png: MediaType  = make["png"]
    transparent inline def gif: MediaType  = make["gif"]
  end image

  object application extends Subtype["application"]:
    inline def octetStream: MediaType      = make["octet-stream"]
    transparent inline def json: MediaType = make["json"]
    transparent inline def xml: MediaType  = make["xml"]
    transparent inline def pdf: MediaType  = make["pdf"]
    transparent inline def zip: MediaType  = make["zip"]
  end application

  object audio extends Subtype["audio"]
  object video extends Subtype["video"]

  object text extends Subtype["text"]:
    transparent inline def plain: MediaType = make["plain"]
    transparent inline def html: MediaType  = make["html"]
  end text

  object multipart extends Subtype["multipart"]:
    transparent inline def formData: MediaType = make["form-data"]
  end multipart

  private val compressibleTypes: Set[String] = Set(
    text.plain,
    text.html,
    application.json,
    application.xml
  )

  private val binaryTypes: Set[String] = Set(
    application.octetStream,
    image.jpeg,
    image.png,
    image.gif,
    application.pdf,
    application.zip
  )

  def fromFileName(fileName: String): MediaType =
    fileName.toLowerCase match
      case f if f.endsWith(".json")                       => application.json
      case f if f.endsWith(".xml")                        => application.xml
      case f if f.endsWith(".pdf")                        => application.pdf
      case f if f.endsWith(".zip")                        => application.zip
      case f if f.endsWith(".txt")                        => text.plain
      case f if f.endsWith(".html") || f.endsWith(".htm") => text.html
      case f if f.endsWith(".jpg") || f.endsWith(".jpeg") => image.jpeg
      case f if f.endsWith(".png")                        => image.png
      case f if f.endsWith(".gif")                        => image.gif
      case _                                              => application.octetStream
