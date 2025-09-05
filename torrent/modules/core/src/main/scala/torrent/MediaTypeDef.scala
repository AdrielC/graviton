package torrent

import io.github.iltotore.iron.constraint.all.*
import io.github.iltotore.iron.{ zio as _, * }
import torrent.schemas.RefinedTypeExt

import zio.schema.*
import zio.schema.annotation.{ description, transientCase }

enum MediaCategory derives Schema:
  case Text, Document, Image, Video, Audio, Archive, Binary, Unknown

type MediaPart = MediaPart.T
object MediaPart
    extends RefinedTypeExt[
      String,
      DescribedAs[
        Not[Blank] & MaxLength[100] & Match["""^[a-zA-Z0-9+\-\.]{1,100}$"""],
        "Must be a valid media type component (alnum, dash, plus, dot)"
      ]
    ]

type ParamKey = ParamKey.T
object ParamKey
    extends RefinedTypeExt[
      String,
      DescribedAs[
        ForAll[LowerCase] & Alphanumeric,
        "Must be a valid parameter key (alnum, lowercase)"
      ]
    ]

object ParamValue
    extends RefinedTypeExt[
      String,
      MaxLength[100]
    ]
type ParamValue = ParamValue.T

sealed trait MediaTypeDef extends Product with Serializable derives Schema:
  def mainType: MediaPart
  def subType: MediaPart
  def fullType: String = s"$mainType/$subType"
  def compressible: Boolean
  def binary: Boolean
  def extensions: List[String]
  def category: MediaCategory

object MediaTypeDef:

  @transientCase sealed trait Textual      extends MediaTypeDef
  @transientCase sealed trait DocumentLike extends MediaTypeDef
  @transientCase sealed trait ImageType    extends MediaTypeDef
  @transientCase sealed trait VideoType    extends MediaTypeDef
  @transientCase sealed trait Audio        extends MediaTypeDef
  @transientCase sealed trait ArchiveType  extends MediaTypeDef

  // Legal document types
  case object Pdf extends MediaTypeDef with DocumentLike:
    val mainType     = MediaPart("application")
    val subType      = MediaPart("pdf")
    val compressible = false
    val binary       = true
    val extensions   = List("pdf")
    val category     = MediaCategory.Document

  case object Doc extends MediaTypeDef with DocumentLike:
    val mainType     = MediaPart("application")
    val subType      = MediaPart("msword")
    val compressible = false
    val binary       = true
    val extensions   = List("doc", "dot")
    val category     = MediaCategory.Document

  case object Docx extends MediaTypeDef with DocumentLike:
    val mainType     = MediaPart("application")
    val subType      = MediaPart("vnd.openxmlformats-officedocument.wordprocessingml.document")
    val compressible = false
    val binary       = true
    val extensions   = List("docx", "docm")
    val category     = MediaCategory.Document

  case object Xlsx extends MediaTypeDef with DocumentLike:
    val mainType     = MediaPart("application")
    val subType      = MediaPart("vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    val compressible = false
    val binary       = true
    val extensions   = List("xlsx", "xlsm")
    val category     = MediaCategory.Document

  case object Pptx extends MediaTypeDef with DocumentLike:
    val mainType     = MediaPart("application")
    val subType      = MediaPart("vnd.openxmlformats-officedocument.presentationml.presentation")
    val compressible = false
    val binary       = true
    val extensions   = List("pptx", "ppt")
    val category     = MediaCategory.Document

  // Image types for scanned documents
  case object Tiff extends MediaTypeDef with ImageType:
    val mainType     = MediaPart("image")
    val subType      = MediaPart("tiff")
    val compressible = false
    val binary       = true
    val extensions   = List("tif", "tiff")
    val category     = MediaCategory.Image

  case object Jpeg extends MediaTypeDef with ImageType:
    val mainType     = MediaPart("image")
    val subType      = MediaPart("jpeg")
    val compressible = false
    val binary       = true
    val extensions   = List("jpg", "jpeg")
    val category     = MediaCategory.Image

  case object Png extends MediaTypeDef with ImageType:
    val mainType     = MediaPart("image")
    val subType      = MediaPart("png")
    val compressible = false
    val binary       = true
    val extensions   = List("png")
    val category     = MediaCategory.Image

  // Audio/Video for depositions
  case object Mp4 extends MediaTypeDef with VideoType:
    val mainType     = MediaPart("video")
    val subType      = MediaPart("mp4")
    val compressible = false
    val binary       = true
    val extensions   = List("mp4", "m4v")
    val category     = MediaCategory.Video

  case object Mov extends MediaTypeDef with VideoType:
    val mainType     = MediaPart("video")
    val subType      = MediaPart("quicktime")
    val compressible = false
    val binary       = true
    val extensions   = List("mov", "qt")
    val category     = MediaCategory.Video

  case object Avi extends MediaTypeDef with VideoType:
    val mainType     = MediaPart("video")
    val subType      = MediaPart("x-msvideo")
    val compressible = false
    val binary       = true
    val extensions   = List("avi")
    val category     = MediaCategory.Video

  case object Webm extends MediaTypeDef with VideoType:
    val mainType     = MediaPart("video")
    val subType      = MediaPart("webm")
    val compressible = false
    val binary       = true
    val extensions   = List("webm")
    val category     = MediaCategory.Video

  case object Mp3 extends MediaTypeDef with Audio:
    val mainType     = MediaPart("audio")
    val subType      = MediaPart("mpeg")
    val compressible = false
    val binary       = true
    val extensions   = List("mp3")
    val category     = MediaCategory.Audio

  case object Wav extends MediaTypeDef with Audio:
    val mainType     = MediaPart("audio")
    val subType      = MediaPart("x-wav")
    val compressible = false
    val binary       = true
    val extensions   = List("wav")
    val category     = MediaCategory.Audio

  case object M4a extends MediaTypeDef with Audio:
    val mainType     = MediaPart("audio")
    val subType      = MediaPart("mp4")
    val compressible = false
    val binary       = true
    val extensions   = List("m4a")
    val category     = MediaCategory.Audio

  case object Ogg extends MediaTypeDef with Audio:
    val mainType     = MediaPart("audio")
    val subType      = MediaPart("ogg")
    val compressible = false
    val binary       = true
    val extensions   = List("ogg")
    val category     = MediaCategory.Audio

  // Text formats
  case object Xml extends MediaTypeDef with Textual:
    val mainType     = MediaPart("application")
    val subType      = MediaPart("xml")
    val compressible = true
    val binary       = false
    val extensions   = List("xml", "xsd")
    val category     = MediaCategory.Text

  case object Json extends MediaTypeDef with Textual:
    val mainType     = MediaPart("application")
    val subType      = MediaPart("json")
    val compressible = true
    val binary       = false
    val extensions   = List("json")
    val category     = MediaCategory.Text

  case object Eml extends MediaTypeDef with Textual:
    val mainType     = MediaPart("message")
    val subType      = MediaPart("rfc822")
    val compressible = true
    val binary       = false
    val extensions   = List("eml")
    val category     = MediaCategory.Text

  case object PlainText extends MediaTypeDef with Textual:
    val mainType     = MediaPart("text")
    val subType      = MediaPart("plain")
    val compressible = true
    val binary       = false
    val extensions   = List("txt", "text", "log")
    val category     = MediaCategory.Text

  case object Csv extends MediaTypeDef with Textual:
    val mainType     = MediaPart("text")
    val subType      = MediaPart("csv")
    val compressible = true
    val binary       = false
    val extensions   = List("csv")
    val category     = MediaCategory.Text

  // Archives
  case object Zip extends MediaTypeDef with ArchiveType:
    val mainType     = MediaPart("application")
    val subType      = MediaPart("zip")
    val compressible = false
    val binary       = true
    val extensions   = List("zip")
    val category     = MediaCategory.Archive

  case object Gzip extends MediaTypeDef with ArchiveType:
    val mainType     = MediaPart("application")
    val subType      = MediaPart("gzip")
    val compressible = false
    val binary       = true
    val extensions   = List("gz")
    val category     = MediaCategory.Archive

  // Fallback
  case object OctetStream extends MediaTypeDef:
    val mainType     = MediaPart("application")
    val subType      = MediaPart("octet-stream")
    val compressible = false
    val binary       = true
    val extensions   = Nil
    val category     = MediaCategory.Binary

  val all: List[MediaTypeDef] = List(
    Pdf,
    Doc,
    Docx,
    Xlsx,
    Pptx,
    Tiff,
    Jpeg,
    Png,
    Mp4,
    Mov,
    Avi,
    Webm,
    Mp3,
    Wav,
    M4a,
    Ogg,
    Xml,
    Json,
    Eml,
    PlainText,
    Csv,
    Zip,
    Gzip,
    OctetStream
  )

  val byFullType: Map[String, MediaTypeDef] =
    all.map(t => t.fullType.toLowerCase -> t).toMap

  val byExtension: Map[String, MediaTypeDef] =
    all.flatMap(t => t.extensions.map(ext => ext.toLowerCase -> t)).toMap

/**
 * Runtime wrapper with parameters
 */
final case class MediaTypeInstance(
  base:       MediaTypeDef,
  parameters: Map[ParamKey, ParamValue] = Map.empty
):
  def fullType: String = base.fullType

  def fullTypeWithParams: String =
    if parameters.isEmpty then base.fullType
    else base.fullType + parameters.map { case (k, v) => s"; $k=$v" }.mkString

  def charset: Option[String] = parameters.collectFirst {
    case (key, value) if key == "charset" => value
  }

  override def toString: String = fullTypeWithParams

object MediaTypeInstance:
  given Schema[MediaCategory] = DeriveSchema.gen

  given Schema[MediaTypeDef]      = DeriveSchema.gen[MediaTypeDef]
  given Schema[MediaTypeInstance] = DeriveSchema.gen

  def fromString(raw: String): MediaTypeInstance =
    val parts    = raw.split(";").map(_.trim)
    val typePart = parts.headOption.getOrElse("application/octet-stream").toLowerCase
    val params   = parts.tail.flatMap { p =>
      val kv = p.split("=", 2).map(_.trim)
      if kv.length == 2 then
        for {
          k <- ParamKey.either(kv(0)).toOption
          v <- ParamValue.either(kv(1)).toOption
        } yield k -> v
      else None
    }.toMap

    val base = MediaTypeDef.byFullType.getOrElse(typePart, MediaTypeDef.OctetStream)
    MediaTypeInstance(base, params)

  def fromExtension(ext: String): MediaTypeInstance =
    val cleanExt = ext.toLowerCase.stripPrefix(".")
    val base     = MediaTypeDef.byExtension.getOrElse(cleanExt, MediaTypeDef.OctetStream)
    MediaTypeInstance(base)
