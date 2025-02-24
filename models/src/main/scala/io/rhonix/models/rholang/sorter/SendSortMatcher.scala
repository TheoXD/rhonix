package io.rhonix.models.rholang.sorter

import cats.effect.Sync
import io.rhonix.models.{Par, Send}
import io.rhonix.models.rholang.implicits._
import cats.implicits._

private[sorter] object SendSortMatcher extends Sortable[Send] {
  def sortMatch[F[_]: Sync](s: Send): F[ScoredTerm[Send]] =
    for {
      sortedChan <- Sortable.sortMatch(s.chan)
      sortedData <- s.data.toList.traverse(Sortable[Par].sortMatch[F])
      sortedSend = Send(
        chan = sortedChan.term,
        data = sortedData.map(_.term),
        persistent = s.persistent,
        locallyFree = s.locallyFree,
        connectiveUsed = s.connectiveUsed
      )
      persistentScore     = if (s.persistent) 1L else 0L
      connectiveUsedScore = if (s.connectiveUsed) 1L else 0L
      sendScore = Node(
        Score.SEND,
        Seq(Leaf(persistentScore)) ++ Seq(sortedChan.score) ++ sortedData.map(_.score) ++ Seq(
          Leaf(connectiveUsedScore)
        ): _*
      )
    } yield ScoredTerm(sortedSend, sendScore)
}
