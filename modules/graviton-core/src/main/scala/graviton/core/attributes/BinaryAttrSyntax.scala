package graviton.core.attributes

import graviton.core.types.*
import kyo.Record
import kyo.Record.`~`

object BinaryAttrSyntax:
  extension [F[_], Fields](rec: Record[Fields])
    inline def withSize(value: F[FileSize]): Record[Fields & ("fileSize" ~ F[FileSize])] =
      rec & ("fileSize" ~ value)

    inline def withChunkCount(value: F[ChunkCount]): Record[Fields & ("chunkCount" ~ F[ChunkCount])] =
      rec & ("chunkCount" ~ value)

    inline def withMime(value: F[Mime]): Record[Fields & ("mime" ~ F[Mime])] =
      rec & ("mime" ~ value)

    inline def withDigests(value: F[Map[Algo, HexLower]]): Record[Fields & ("digests" ~ F[Map[Algo, HexLower]])] =
      rec & ("digests" ~ value)

    inline def withCustom(value: F[Map[String, String]]): Record[Fields & ("custom" ~ F[Map[String, String]])] =
      rec & ("custom" ~ value)
