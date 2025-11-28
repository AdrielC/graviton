package graviton.core.attributes

import graviton.core.model.{ChunkCount, FileSize}
import graviton.core.types.*
import kyo.Record
import kyo.Record.`~`
import kyo.Tag
import kyo.Tag.given

object BinaryAttr:

  type Base =
    "size" ~ FileSize & "chunkCount" ~ ChunkCount & "mime" ~ Mime & "digests" ~ Map[Algo, HexLower] & "custom" ~ Map[String, String]

  type Fields[F[_]] =
    "size" ~ F[FileSize] & "chunkCount" ~ F[ChunkCount] & "mime" ~ F[Mime] & "digests" ~ F[Map[Algo, HexLower]] &
      "custom" ~ F[Map[String, String]]

  type Rec[F[_]] = Record[Fields[F]]

  type Id[A] = A

  type Plain   = Rec[Id]
  type Partial = Rec[Option]

  import BinaryAttrSyntax.*

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
    val record =
      Record.empty
        .withSize(size)
        .withChunkCount(chunkCount)
        .withMime(mime)
        .withDigests(digests)
        .withCustom(custom)
    record.asInstanceOf[Rec[F]]

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
    inline private def lookup[F[_], A](rec: Rec[F], name: String): F[A] =
      rec.toMap
        .collectFirst {
          case (field, value) if field.name == name =>
            value.asInstanceOf[F[A]]
        }
        .getOrElse(
          throw new NoSuchElementException(s"Field '$name' missing in BinaryAttr record")
        )

    extension [F[_]](rec: Rec[F])
      inline def sizeValue: F[FileSize] =
        lookup(rec, "size")

      inline def chunkCountValue: F[ChunkCount] =
        lookup(rec, "chunkCount")

      inline def mimeValue: F[Mime] =
        lookup(rec, "mime")

      inline def digestsValue: F[Map[Algo, HexLower]] =
        lookup(rec, "digests")

      inline def customValue: F[Map[String, String]] =
        lookup(rec, "custom")

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
