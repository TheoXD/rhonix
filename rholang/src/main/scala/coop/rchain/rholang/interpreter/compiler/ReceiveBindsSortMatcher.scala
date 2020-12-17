package coop.rchain.rholang.interpreter.compiler

import cats.effect.Sync
import coop.rchain.models.rholang.sorter.ReceiveSortMatcher.sortBind
import coop.rchain.models.{Par, ReceiveBind, Var}
import cats.implicits._
import coop.rchain.models.rholang.sorter._
import coop.rchain.models.rholang.implicits._

object ReceiveBindsSortMatcher {
  // Used during normalize to presort the binds.
  def preSortBinds[F[_]: Sync, T](
      binds: Seq[(Seq[Par], Option[Var], Par, FreeMap[T])]
  ): F[Seq[(ReceiveBind, FreeMap[T])]] = {
    val bindSortings = binds.toList
      .map {
        case (
            patterns: Seq[Par],
            remainder: Option[Var],
            channel: Par,
            knownFree: FreeMap[T]
            ) =>
          for {
            sortedBind <- sortBind(
                           ReceiveBind(
                             patterns,
                             channel,
                             remainder,
                             freeCount = knownFree.countNoWildcards
                           )
                         )
          } yield ScoredTerm((sortedBind.term, knownFree), sortedBind.score)
      }

    for {
      binds <- bindSortings.sequence
    } yield binds.sorted.map(_.term)
  }

}
