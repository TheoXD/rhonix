package io.rhonix.rholang.interpreter
import io.rhonix.models.Var.VarInstance.FreeVar
import io.rhonix.models._
import io.rhonix.models.rholang.implicits._
import io.rhonix.models.rholang.sorter.Sortable
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import io.rhonix.models.rholang.sorter.ScoredTerm
import monix.eval.Coeval

import scala.collection.immutable.BitSet

class SortSpec extends AnyFlatSpec with Matchers {

  "GroundSortMatcher" should "discern sets with and without remainder" in {
    assertOrder[Expr](
      ParSet(Seq.empty),
      ParSet(Seq.empty, remainder = Some(FreeVar(0)))
    )
  }

  it should "discern maps with and without remainder" in {
    assertOrder[Expr](
      ParMap(Seq.empty),
      ParMap(
        Seq.empty,
        connectiveUsed = false,
        locallyFree = BitSet(),
        remainder = Some(Var(FreeVar(0)))
      )
    )
  }

  "ReceiveSortMatcher" should "discern Receives with and without peek" in {
    assertOrder[Receive](
      Receive(peek = false),
      Receive(peek = true)
    )
  }

  private def assertOrder[T: Sortable](smaller: T, bigger: T): Any = {
    val left: ScoredTerm[T]  = checkSortingAndScore(smaller)
    val right: ScoredTerm[T] = checkSortingAndScore(bigger)
    assert(Ordering[ScoredTerm[T]].compare(left, right) < 0)
  }

  def checkSortingAndScore[T: Sortable](term: T): ScoredTerm[T] = {
    val scored: ScoredTerm[T] = Sortable[T].sortMatch[Coeval](term).value
    assert(scored.term == term, "Either input term not sorted or sorting returned wrong results")
    scored
  }
}
