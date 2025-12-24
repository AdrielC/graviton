package graviton.core.attributes

import graviton.core.types.*
import kyo.Record
import kyo.Record.`~`
import kyo.Tag
import kyo.Tag.given

object BinaryAttr:

  type Fields[F[_]] =
    "fileSize" ~ F[FileSize] & "chunkCount" ~ F[ChunkCount] & "mime" ~ F[Mime] & "digests" ~ F[Map[Algo, HexLower]] &
      "custom" ~ F[Map[String, String]]

  type Base = Fields[Id]

  type Rec[F[_]] = Record[Fields[F]]

  opaque type Id[+A] <: A = A
  object Id:
    def apply[A](a: A): Id[A]            = a
    extension [A](a: Id[A]) def value: A = a

  type Plain   = Rec[Id]
  type Partial = Rec[Option]

  import BinaryAttrSyntax.*

  def empty: Record[Any] =
    Record.empty

  inline def build[F[_]](
    size: F[FileSize],
    chunkCount: F[ChunkCount],
    mime: F[Mime],
    digests: F[Map[Algo, HexLower]],
    custom: F[Map[String, String]],
  )(
    using Tag[F[FileSize]],
    Tag[F[ChunkCount]],
    Tag[F[Mime]],
    Tag[F[Map[Algo, HexLower]]],
    Tag[F[Map[String, String]]],
  ): Rec[F] =

    Record.empty
      .withSize(size)
      .withChunkCount(chunkCount)
      .withMime(mime)
      .withDigests(digests)
      .withCustom(custom)

  def partial(
    size: Option[FileSize] = None,
    chunkCount: Option[ChunkCount] = None,
    mime: Option[Mime] = None,
    digests: Option[Map[Algo, HexLower]] = Some(Map.empty),
    custom: Option[Map[String, String]] = Some(Map.empty),
  ): Partial =
    build[Option](size, chunkCount, mime, digests, custom)(
      using scala.compiletime.summonInline[Tag[Option[FileSize]]],
      scala.compiletime.summonInline[Tag[Option[ChunkCount]]],
      scala.compiletime.summonInline[Tag[Option[Mime]]],
      scala.compiletime.summonInline[Tag[Option[Map[Algo, HexLower]]]],
      scala.compiletime.summonInline[Tag[Option[Map[String, String]]]],
    )

  def plain(
    size: FileSize,
    chunkCount: ChunkCount,
    mime: Mime,
    digests: Map[Algo, HexLower],
    custom: Map[String, String],
  ): Plain =
    build[Id](size, chunkCount, mime, digests, custom)(
      using scala.compiletime.summonInline[Tag[Id[FileSize]]],
      scala.compiletime.summonInline[Tag[Id[ChunkCount]]],
      scala.compiletime.summonInline[Tag[Id[Mime]]],
      scala.compiletime.summonInline[Tag[Id[Map[Algo, HexLower]]]],
      scala.compiletime.summonInline[Tag[Id[Map[String, String]]]],
    )

  object Access:

    extension [F[_]](rec: Rec[F])
      inline def sizeValue: F[FileSize] = rec.fileSize

      inline def chunkCountValue: F[ChunkCount] =
        rec.chunkCount

      inline def mimeValue: F[Mime] =
        rec.mime

      inline def digestsValue: F[Map[Algo, HexLower]] =
        rec.digests

      inline def customValue: F[Map[String, String]] =
        rec.custom

  object PartialOps:
    import Access.*

    extension (rec: Partial)
      def copyValues(
        size: Option[FileSize] = rec.sizeValue,
        chunkCount: Option[ChunkCount] = rec.chunkCountValue,
        mime: Option[Mime] = rec.mimeValue,
        digests: Option[Map[Algo, HexLower]] = rec.digestsValue,
        custom: Option[Map[String, String]] = rec.customValue,
      ): Partial =
        partial(size, chunkCount, mime, digests, custom)

      def digestsOrEmpty: Map[Algo, HexLower] =
        rec.digestsValue.getOrElse(Map.empty)

      def customOrEmpty: Map[String, String] =
        rec.customValue.getOrElse(Map.empty)

  given Tag[FileSize]                    =
    scala.compiletime.summonInline[Tag[Long]].asInstanceOf[Tag[FileSize]]
  given Tag[Mime]                        = Tag.derive
  given Tag[HexLower]                    = Tag.derive
  given Tag[Algo]                        = Tag.derive
  given Tag[String]                      = Tag.derive
  given [A: Tag]: Tag[Option[A]]         = Tag.derive
  given [K: Tag, V: Tag]: Tag[Map[K, V]] = Tag.derive
