package io.rhonix.models.rholang.sorter

import cats.effect.Sync
import io.rhonix.models.{Expr, Match, MatchCase}
import cats.implicits._

private[sorter] object MatchSortMatcher extends Sortable[Match] {
  def sortMatch[F[_]: Sync](m: Match): F[ScoredTerm[Match]] = {

    def sortCase(matchCase: MatchCase): F[ScoredTerm[MatchCase]] =
      for {
        sortedPattern  <- Sortable.sortMatch(matchCase.pattern)
        sortedBody     <- Sortable.sortMatch(matchCase.source)
        freeCountScore = Leaf(matchCase.freeCount.toLong)
      } yield ScoredTerm(
        MatchCase(sortedPattern.term, sortedBody.term, matchCase.freeCount),
        Node(Seq(sortedPattern.score, sortedBody.score, freeCountScore))
      )
    for {
      sortedValue         <- Sortable.sortMatch(m.target)
      scoredCases         <- m.cases.toList.traverse(sortCase)
      connectiveUsedScore = if (m.connectiveUsed) 1L else 0L
    } yield ScoredTerm(
      Match(sortedValue.term, scoredCases.map(_.term), m.locallyFree, m.connectiveUsed),
      Node(
        Score.MATCH,
        Seq(sortedValue.score) ++ scoredCases.map(_.score) ++ Seq(Leaf(connectiveUsedScore)): _*
      )
    )
  }
}
