package graviton.core

import zio.schema.*
import java.util.UUID
import java.time.Instant

final case class BinaryAttributeKey[+A](id: String)(using
    val schema: Schema[? <: A]
)
object BinaryAttributeKey:

  val size: BinaryAttributeKey[Long] =
    BinaryAttributeKey[Long]("attr:size")(using Schema[Long])

  val filename: BinaryAttributeKey[String] =
    BinaryAttributeKey[String]("attr:filename")(using Schema[String])

  val contentType: BinaryAttributeKey[String] =
    BinaryAttributeKey[String]("attr:contentType")(using Schema[String])

  val createdAt: BinaryAttributeKey[Instant] =
    BinaryAttributeKey[Instant]("attr:createdAt")(using Schema[Instant])

  val ownerOrgId: BinaryAttributeKey[UUID] =
    BinaryAttributeKey[UUID]("attr:ownerOrgId")(using Schema[UUID])
