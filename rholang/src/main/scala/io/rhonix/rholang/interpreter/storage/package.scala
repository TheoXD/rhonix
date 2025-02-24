package io.rhonix.rholang.interpreter

import cats.effect.Sync
import cats.mtl.implicits._
import cats.syntax.all._
import io.rhonix.metrics.Span
import io.rhonix.models.Var.VarInstance.FreeVar
import io.rhonix.models._
import io.rhonix.models.rholang.implicits._
import io.rhonix.models.serialization.implicits.mkProtobufInstance
import io.rhonix.rholang.interpreter.matcher._
import io.rhonix.rspace.{Match => StorageMatch}
import io.rhonix.shared.Serialize

//noinspection ConvertExpressionToSAM
package object storage {

  /* Match instance */

  private def toSeq(fm: FreeMap, max: Int): Seq[Par] =
    (0 until max).map { (i: Int) =>
      fm.get(i) match {
        case Some(par) => par
        case None      => Par.defaultInstance
      }
    }

  def matchListPar[F[_]: Sync: Span]: StorageMatch[F, BindPattern, ListParWithRandom] =
    new StorageMatch[F, BindPattern, ListParWithRandom] {
      def get(
          pattern: BindPattern,
          data: ListParWithRandom
      ): F[Option[ListParWithRandom]] = {
        type R[A] = MatcherMonadT[F, A]
        implicit val matcherMonadError = implicitly[Sync[R]]
        for {
          matchResult <- runFirst[F, Seq[Par]](
                          SpatialMatcher
                            .foldMatch[R, Par, Par](
                              data.pars,
                              pattern.patterns,
                              pattern.remainder
                            )
                        )
        } yield {
          matchResult.map {
            case (freeMap, caughtRem) =>
              val remainderMap = pattern.remainder match {
                case Some(Var(FreeVar(level))) =>
                  freeMap + (level -> VectorPar().addExprs(EList(caughtRem.toVector)))
                case _ => freeMap
              }
              ListParWithRandom(
                toSeq(remainderMap, pattern.freeCount),
                data.randomState
              )
          }
        }
      }
    }

  /* Serialize instances */

  implicit val serializeBindPattern: Serialize[BindPattern] =
    mkProtobufInstance(BindPattern)

  implicit val serializePar: Serialize[Par] =
    mkProtobufInstance(Par)

  implicit val serializePars: Serialize[ListParWithRandom] =
    mkProtobufInstance(ListParWithRandom)

  implicit val serializeTaggedContinuation: Serialize[TaggedContinuation] =
    mkProtobufInstance(TaggedContinuation)
}
