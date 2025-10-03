package graviton.core.keys

final case class ViewTransform(name: String, args: Map[String, String], scope: Option[String]):
  def normalizedArgs: List[(String, String)] = args.toList.sortBy(_._1)
