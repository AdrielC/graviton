package graviton.collections

final case class DisjointSets[A] private (
  parent: Map[A, A],
  rank: Map[A, Int],
):

  def add(a: A): DisjointSets[A] =
    if parent.contains(a) then this
    else copy(parent = parent.updated(a, a), rank = rank.updated(a, 0))

  def find(a: A): (DisjointSets[A], A) =
    val ensured = add(a)
    ensured.parent(a) match
      case p if p == a => (ensured, a)
      case p           =>
        val (next, root) = ensured.find(p)
        val compressed   = next.parent.updated(a, root)
        (next.copy(parent = compressed), root)

  def union(a: A, b: A): DisjointSets[A] =
    val (afterA, rootA) = find(a)
    val (afterB, rootB) = afterA.find(b)
    if rootA == rootB then afterB
    else
      val rankA = afterB.rank.getOrElse(rootA, 0)
      val rankB = afterB.rank.getOrElse(rootB, 0)
      if rankA < rankB then afterB.copy(parent = afterB.parent.updated(rootA, rootB))
      else if rankA > rankB then afterB.copy(parent = afterB.parent.updated(rootB, rootA))
      else
        afterB.copy(
          parent = afterB.parent.updated(rootB, rootA),
          rank = afterB.rank.updated(rootA, rankA + 1),
        )

  def connected(a: A, b: A): (DisjointSets[A], Boolean) =
    val (afterA, rootA) = find(a)
    val (afterB, rootB) = afterA.find(b)
    (afterB, rootA == rootB)

  def componentMembers(a: A): (DisjointSets[A], Set[A]) =
    val (afterFind, root) = find(a)
    val (finalDs, acc)    = afterFind.parent.keys.foldLeft((afterFind, Set.empty[A])) { case ((ds, set), node) =>
      val (next, nodeRoot) = ds.find(node)
      if nodeRoot == root then (next, set + node) else (next, set)
    }
    (finalDs, acc)

  def allMembers: (DisjointSets[A], Set[A]) =
    parent.keys.foldLeft((this, Set.empty[A])) { case ((ds, set), node) =>
      val (next, _) = ds.find(node)
      (next, set + node)
    }

object DisjointSets:
  def empty[A]: DisjointSets[A] = DisjointSets(Map.empty, Map.empty)
