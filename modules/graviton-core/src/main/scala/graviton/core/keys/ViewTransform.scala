package graviton.core.keys

import zio.schema.{DeriveSchema, Schema}

final case class ViewTransform(name: String, args: Map[String, String], scope: Option[String]):
  def normalizedArgs: List[(String, String)] = args.toList.sortBy(_._1)

object ViewTransform:
  given Schema[ViewTransform] = DeriveSchema.gen[ViewTransform]
