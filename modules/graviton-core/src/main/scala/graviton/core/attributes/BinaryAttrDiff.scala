package graviton.core.attributes

import BinaryAttr.Access.*
import kyo.Tag
import kyo.Tag.given

sealed trait AttrDiff[+A]
object AttrDiff:
  case object Missing                                   extends AttrDiff[Nothing]
  final case class OnlyAdvertised[A](value: A)          extends AttrDiff[A]
  final case class OnlyConfirmed[A](value: A)           extends AttrDiff[A]
  final case class Both[A](advertised: A, confirmed: A) extends AttrDiff[A]

object BinaryAttrDiff:
  type Record = BinaryAttr.Rec[AttrDiff]

  given attrDiffTag[A](using Tag[A]): Tag[AttrDiff[A]] = Tag.derive

  def compute(advertised: BinaryAttr.Partial, confirmed: BinaryAttr.Partial): Record =
    BinaryAttr.build[AttrDiff](
      size = diff(advertised.sizeValue, confirmed.sizeValue),
      chunkCount = diff(advertised.chunkCountValue, confirmed.chunkCountValue),
      mime = diff(advertised.mimeValue, confirmed.mimeValue),
      digests = diff(advertised.digestsValue, confirmed.digestsValue),
      custom = diff(advertised.customValue, confirmed.customValue),
    )(
      using scala.compiletime.summonInline[Tag[AttrDiff[graviton.core.types.FileSize]]],
      scala.compiletime.summonInline[Tag[AttrDiff[graviton.core.types.ChunkCount]]],
      scala.compiletime.summonInline[Tag[AttrDiff[graviton.core.types.Mime]]],
      scala.compiletime.summonInline[Tag[AttrDiff[Map[graviton.core.types.Algo, graviton.core.types.HexLower]]]],
      scala.compiletime.summonInline[Tag[AttrDiff[Map[String, String]]]],
    )

  private def diff[A](advertised: Option[A], confirmed: Option[A]): AttrDiff[A] =
    (advertised, confirmed) match
      case (Some(a), Some(b)) => AttrDiff.Both(a, b)
      case (Some(a), None)    => AttrDiff.OnlyAdvertised(a)
      case (None, Some(b))    => AttrDiff.OnlyConfirmed(b)
      case (None, None)       => AttrDiff.Missing
end BinaryAttrDiff
