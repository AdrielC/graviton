package graviton.core.attributes

import graviton.core.types.*
import kyo.Record
import kyo.Record.`~`
import kyo.Tag
import kyo.Tag.given

object BinaryAttr:

  type Fields[F[_]] =
    "fileSize" ~ F[FileSize] & "chunkCount" ~ F[ChunkCount] & "mime" ~ F[Mime] & "digests" ~ F[Map[Algo, HexLower]] &
      "custom" ~ F[Map[CustomAttributeName, CustomAttributeValue]]

  type Base = Fields[Id]

  type Rec[F[_]] = Record[Fields[F]]

  opaque type Id[+A] <: A = A
  object Id:
    def apply[A](a: A): Id[A]            = a
    extension [A](a: Id[A]) def value: A = a

  type Plain   = Rec[Id]
  type Partial = Rec[Option]

  // Kyo record keys include their Tag value. Build container tags dynamically
  // from the nominal, companion-owned tags supplied by RefinedTypeExt and
  // RefinedSubtypeExt, then retain the exact instances used by reads and writes.
  private def optionTag[A](using Tag[A]): Tag[Option[A]]         = Tag.derive
  private def mapTag[K, V](using Tag[K], Tag[V]): Tag[Map[K, V]] = Tag.derive

  private val partialSizeTag: Tag[Option[FileSize]]                                         = optionTag[FileSize]
  private val partialChunkCountTag: Tag[Option[ChunkCount]]                                 = optionTag[ChunkCount]
  private val partialMimeTag: Tag[Option[Mime]]                                             = optionTag[Mime]
  private val partialDigestsTag: Tag[Option[Map[Algo, HexLower]]]                           =
    optionTag[Map[Algo, HexLower]](using mapTag[Algo, HexLower])
  private val partialCustomTag: Tag[Option[Map[CustomAttributeName, CustomAttributeValue]]] =
    optionTag[Map[CustomAttributeName, CustomAttributeValue]](
      using mapTag[CustomAttributeName, CustomAttributeValue]
    )

  import BinaryAttrSyntax.*

  def empty: Record[Any] =
    Record.empty

  inline def build[F[_]](
    size: F[FileSize],
    chunkCount: F[ChunkCount],
    mime: F[Mime],
    digests: F[Map[Algo, HexLower]],
    custom: F[Map[CustomAttributeName, CustomAttributeValue]],
  )(
    using Tag[F[FileSize]],
    Tag[F[ChunkCount]],
    Tag[F[Mime]],
    Tag[F[Map[Algo, HexLower]]],
    Tag[F[Map[CustomAttributeName, CustomAttributeValue]]],
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
    custom: Option[Map[CustomAttributeName, CustomAttributeValue]] = Some(Map.empty),
  ): Partial =
    build[Option](size, chunkCount, mime, digests, custom)(
      using partialSizeTag,
      partialChunkCountTag,
      partialMimeTag,
      partialDigestsTag,
      partialCustomTag,
    )

  def plain(
    size: FileSize,
    chunkCount: ChunkCount,
    mime: Mime,
    digests: Map[Algo, HexLower],
    custom: Map[CustomAttributeName, CustomAttributeValue],
  ): Plain =
    build[Id](size, chunkCount, mime, digests, custom)(
      using scala.compiletime.summonInline[Tag[Id[FileSize]]],
      scala.compiletime.summonInline[Tag[Id[ChunkCount]]],
      scala.compiletime.summonInline[Tag[Id[Mime]]],
      scala.compiletime.summonInline[Tag[Id[Map[Algo, HexLower]]]],
      scala.compiletime.summonInline[Tag[Id[Map[CustomAttributeName, CustomAttributeValue]]]],
    )

  object Access:
    extension (rec: Partial)
      def sizeValue: Option[FileSize] =
        given Tag[Option[FileSize]] = partialSizeTag
        rec.fileSize

      def chunkCountValue: Option[ChunkCount] =
        given Tag[Option[ChunkCount]] = partialChunkCountTag
        rec.chunkCount

      def mimeValue: Option[Mime] =
        given Tag[Option[Mime]] = partialMimeTag
        rec.mime

      def digestsValue: Option[Map[Algo, HexLower]] =
        given Tag[Option[Map[Algo, HexLower]]] = partialDigestsTag
        rec.digests

      def customValue: Option[Map[CustomAttributeName, CustomAttributeValue]] =
        given Tag[Option[Map[CustomAttributeName, CustomAttributeValue]]] = partialCustomTag
        rec.custom

  object PartialOps:
    import Access.*

    extension (rec: Partial)
      def copyValues(
        size: Option[FileSize] = rec.sizeValue,
        chunkCount: Option[ChunkCount] = rec.chunkCountValue,
        mime: Option[Mime] = rec.mimeValue,
        digests: Option[Map[Algo, HexLower]] = rec.digestsValue,
        custom: Option[Map[CustomAttributeName, CustomAttributeValue]] = rec.customValue,
      ): Partial =
        partial(size, chunkCount, mime, digests, custom)

      def digestsOrEmpty: Map[Algo, HexLower] =
        rec.digestsValue.getOrElse(Map.empty)

      def customOrEmpty: Map[CustomAttributeName, CustomAttributeValue] =
        rec.customValue.getOrElse(Map.empty)
