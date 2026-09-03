package graviton.core.attributes

import graviton.core.types.*
import kyo.Tag
import zio.test.*

object BinaryAttrTagSpec extends ZIOSpecDefault:

  private val algoTag: Tag[Algo]             = summon[Tag[Algo]]
  private val hexTag: Tag[HexLower]          = summon[Tag[HexLower]]
  private val fileSizeTag: Tag[FileSize]     = summon[Tag[FileSize]]
  private val chunkCountTag: Tag[ChunkCount] = summon[Tag[ChunkCount]]

  override def spec =
    test("refined aliases receive distinct nominal Kyo tags from their companions") {
      assertTrue(
        algoTag =!= hexTag,
        fileSizeTag =!= chunkCountTag,
        algoTag.show.endsWith("Algo$"),
        hexTag.show.endsWith("HexLower$"),
        fileSizeTag.show.endsWith("FileSize$"),
        chunkCountTag.show.endsWith("ChunkCount$"),
      )
    }
