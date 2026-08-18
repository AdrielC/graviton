package graviton.core.uf

final case class UnionFind[A](parents: Map[A, A]):
  def find(a: A): A =
    parents.get(a) match
      case Some(parent) if parent != a => find(parent)
      case _                           => a

  def union(a: A, b: A): UnionFind[A] =
    val rootA = find(a)
    val rootB = find(b)
    if rootA == rootB then this else UnionFind(parents.updated(rootA, rootB))

object UnionFind:
  def empty[A]: UnionFind[A] = UnionFind(Map.empty)
