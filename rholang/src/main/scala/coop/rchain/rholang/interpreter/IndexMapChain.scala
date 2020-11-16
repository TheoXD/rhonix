package coop.rchain.rholang.interpreter

class IndexMapChain[T](val chain: IndexedSeq[DeBruijnIndexMap[T]]) {
  def this() = this(IndexedSeq(DeBruijnIndexMap[T]()))

  def newBinding(binding: (String, T, Int, Int)): IndexMapChain[T] =
    IndexMapChain(chain.updated(0, chain(0).newBinding(binding)))

  def newBindings(bindings: List[(String, T, Int, Int)]): IndexMapChain[T] =
    IndexMapChain(chain.updated(0, chain(0).newBindings(bindings)))

  def absorbFree(binders: DeBruijnLevelMap[T]): (IndexMapChain[T], List[(String, Int, Int)]) = {
    val (headAbsorbed, shadowed) = chain.head.absorbFree(binders)
    (IndexMapChain(chain.updated(0, headAbsorbed)), shadowed)
  }

  def get(varName: String): Option[(Int, T, Int, Int)] = chain.head.get(varName)

  def count: Int =
    chain.head.count

  def depth: Int =
    chain.size - 1

  def pushDown(): IndexMapChain[T] =
    IndexMapChain(DeBruijnIndexMap[T]() +: chain)

  def getDeep(varName: String): Option[((Int, T, Int, Int), Int)] = {
    def getDeepLoop(varName: String, depth: Int): Option[((Int, T, Int, Int), Int)] =
      if (depth < chain.size) {
        chain(depth).get(varName) match {
          case Some(result) => Some((result, depth))
          case None         => getDeepLoop(varName, depth + 1)
        }
      } else {
        None
      }
    getDeepLoop(varName, 1)
  }
}

object IndexMapChain {
  def apply[T](chain: IndexedSeq[DeBruijnIndexMap[T]]): IndexMapChain[T] =
    new IndexMapChain[T](chain)

  def apply[T](): IndexMapChain[T] =
    new IndexMapChain[T]()

  def unapply[T](ic: IndexMapChain[T]): Option[IndexedSeq[DeBruijnIndexMap[T]]] =
    Some(ic.chain)
}
