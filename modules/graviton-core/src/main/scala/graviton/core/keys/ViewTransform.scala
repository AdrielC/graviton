package graviton.core
package keys

import zio.schema.{DeriveSchema, Schema}
import scala.collection.immutable.ListMap

final case class ViewTransform(name: String, args: ListMap[String, String], scope: Option[String]):
  def normalizedArgs: List[(String, String)] = args.toList.sortBy(_._1)

object ViewTransform:

  def apply(name: String, args: Map[String, String], scope: Option[String]): ViewTransform =
    ViewTransform(name, ListMap(args.toList*), scope)

  given Schema[ViewTransform] = DeriveSchema.gen[ViewTransform]
