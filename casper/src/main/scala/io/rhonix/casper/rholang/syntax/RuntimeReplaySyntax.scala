package io.rhonix.casper.rholang.syntax

import cats.data.EitherT
import cats.effect.Sync
import cats.effect.concurrent.Ref
import cats.syntax.all._
import io.rhonix.casper.CasperMetricsSource
import io.rhonix.casper.protocol.{
  CloseBlockSystemDeployData,
  Empty,
  ProcessedDeploy,
  ProcessedSystemDeploy,
  SlashSystemDeployData
}
import io.rhonix.casper.rholang.InterpreterUtil.printDeployErrors
import io.rhonix.casper.rholang.syntax.RuntimeSyntax.SysEvalResult
import io.rhonix.casper.rholang.sysdeploys.{
  CloseBlockDeploy,
  PreChargeDeploy,
  RefundDeploy,
  SlashDeploy
}
import io.rhonix.casper.rholang.types.{
  ReplayFailure,
  SystemDeploy,
  SystemDeployUserError,
  UnusedCOMMEvent
}
import io.rhonix.casper.util.EventConverter
import io.rhonix.catscontrib.Catscontrib._
import io.rhonix.metrics.implicits._
import io.rhonix.metrics.{Metrics, Span}
import io.rhonix.models.Par
import io.rhonix.models.block.StateHash.StateHash
import io.rhonix.models.syntax._
import io.rhonix.rholang.interpreter.SystemProcesses.BlockData
import io.rhonix.rholang.interpreter.{EvaluateResult, ReplayRhoRuntime}
import io.rhonix.rspace.hashing.Blake2b256Hash
import io.rhonix.rspace.merger.EventLogMergingLogic.NumberChannelsEndVal
import io.rhonix.rspace.util.ReplayException
import io.rhonix.shared.{Base16, Log}
import RuntimeReplaySyntax._
import io.rhonix.casper.rholang.BlockRandomSeed
import io.rhonix.casper.syntax._
import io.rhonix.crypto.hash.Blake2b512Random

import io.rhonix.models.{Expr, GPrivate, GUnforgeable, Send}
import scala.util.Random
import scala.collection.immutable.BitSet
import scodec.bits.ByteVector
import scodec.bits.ByteVector.fromHex
import io.rhonix.crypto.{PrivateKey, PublicKey}
import io.rhonix.rholang.interpreter.SystemProcesses.FixedChannels
import io.rhonix.models.GUnforgeable.UnfInstance.GPrivateBody
import io.rhonix.models.Expr.ExprInstance.{GBool, GByteArray, GInt, GString}
import io.rhonix.models.rholang.RhoType
import io.rhonix.models.Var.VarInstance.Wildcard
import io.rhonix.models.Var.WildcardMsg
import io.rhonix.models.{EVar}
import com.google.protobuf.ByteString
import io.rhonix.rholang.interpreter.accounting.Cost

trait RuntimeReplaySyntax {
  implicit final def casperSyntaxRholangRuntimeReplay[F[_]](
      runtime: ReplayRhoRuntime[F]
  ): RuntimeReplayOps[F] = new RuntimeReplayOps[F](runtime)
}

object RuntimeReplaySyntax {
  implicit val RuntimeMetricsSource = Metrics.Source(CasperMetricsSource, "replay-rho-runtime")
}

final class RuntimeReplayOps[F[_]](private val runtime: ReplayRhoRuntime[F]) extends AnyVal {

  /* REPLAY Compute state with deploys (genesis block) and System deploys (regular block) */

  /**
    * Evaluates (and validates) deploys and System deploys with checkpoint to valiate final state hash
    */
  def replayComputeState(startHash: StateHash)(
      rand: Blake2b512Random,
      terms: Seq[ProcessedDeploy],
      systemDeploys: Seq[ProcessedSystemDeploy],
      blockData: BlockData,
      withCostAccounting: Boolean
  )(
      implicit sync: Sync[F],
      span: Span[F],
      log: Log[F]
  ): F[Either[ReplayFailure, (Blake2b256Hash, Seq[NumberChannelsEndVal])]] =
    Span[F].traceI("replay-compute-state") {
      for {
        _ <- runtime.setBlockData(blockData)
        result <- replayDeploys(
                   startHash,
                   rand,
                   terms,
                   systemDeploys,
                   replayDeployE(withCostAccounting)(_, _).value,
                   replayBlockSystemDeployDiag
                 )
      } yield result
    }

  /* REPLAY Deploy evaluators */

  /**
    * Evaluates (and validates) deploys on root hash with checkpoint to validate final state hash
    */
  def replayDeploys(
      startHash: StateHash,
      rand: Blake2b512Random,
      terms: Seq[ProcessedDeploy],
      systemDeploys: Seq[ProcessedSystemDeploy],
      replayDeploy: (
          ProcessedDeploy,
          Blake2b512Random
      ) => F[Either[ReplayFailure, NumberChannelsEndVal]],
      replaySystemDeploy: (
          ProcessedSystemDeploy,
          Blake2b512Random
      ) => F[Either[ReplayFailure, NumberChannelsEndVal]]
  )(
      implicit s: Sync[F],
      span: Span[F]
  ): F[Either[ReplayFailure, (Blake2b256Hash, Vector[NumberChannelsEndVal])]] = {
    type Params[D] = (Seq[D], Vector[NumberChannelsEndVal], Int)

    val deploys = (terms, Vector[NumberChannelsEndVal](), 0).tailRecM {
      case (Seq(), mergeable, _) =>
        mergeable.asRight[ReplayFailure].asRight[Params[ProcessedDeploy]].pure[F]
      case (ts, mergeable, randIndex) =>
        Span[F].traceI("replay-deploy") {
          replayDeploy(ts.head, rand.splitByte(randIndex.toByte)).map { a =>
            a.map(x => (ts.tail, mergeable :+ x, randIndex + 1))
              .swap
              .map(_.asLeft[Vector[NumberChannelsEndVal]])
          }
        }
    }
    val sysDeploys = (systemDeploys, Vector[NumberChannelsEndVal](), terms.length).tailRecM {
      case (Seq(), mergeable, _) =>
        mergeable.asRight[ReplayFailure].asRight[Params[ProcessedSystemDeploy]].pure[F]
      case (ts, mergeable, randIndex) =>
        Span[F].traceI("replay-sys-deploy") {
          replaySystemDeploy(ts.head, rand.splitByte(randIndex.toByte)).map { a =>
            a.map(x => (ts.tail, mergeable :+ x, randIndex + 1))
              .swap
              .map(_.asLeft[Vector[NumberChannelsEndVal]])
          }
        }
    }
    val refT = Ref.of(Vector[NumberChannelsEndVal]()).liftEitherT[ReplayFailure]

    refT.flatMap { mergeable =>
      EitherT
        .liftF(runtime.reset(startHash.toBlake2b256Hash))
        .flatMap(_ => EitherT(deploys).semiflatTap(chs => mergeable.update(_ ++ chs)))
        .flatMap(_ => EitherT(sysDeploys).semiflatTap(chs => mergeable.update(_ ++ chs)))
        .semiflatMap(_ => mergeable.get)
        .semiflatMap { allMergeable =>
          Span[F].traceI("create-checkpoint") {
            runtime.createCheckpoint.map(c => (c.root, allMergeable))
          }
        }
    }.value
  }

  def replayDeployE(withCostAccounting: Boolean)(
      processedDeploy: ProcessedDeploy,
      rand: Blake2b512Random
  )(
      implicit s: Sync[F],
      span: Span[F],
      log: Log[F]
  ): EitherT[F, ReplayFailure, NumberChannelsEndVal] = {
    import io.rhonix.models.rholang.{implicits => toPar}

    val prepaidLookupChannel = FixedChannels.PREPAID_LOOKUP
    val ackChannel: Par = Par(
      unforgeables = Seq(
        GUnforgeable(
          GPrivateBody(
            new GPrivate(ByteString.copyFromUtf8(Random.alphanumeric.take(10).foldLeft("")(_ + _)))
          )
        )
      )
    )

    val data: Seq[Par] = Seq(
      Par(exprs = Seq(Expr(GString(processedDeploy.deploy.data.sponsorPubKey)))),
      Par(exprs = Seq(Expr(GString(Base16.encode(processedDeploy.deploy.pk.bytes))))),
      ackChannel
    )

    val send = Send(
      prepaidLookupChannel,
      data,
      persistent = false,
      BitSet()
    )

    def getSponsorPhlo: F[Long] =
      for {
        _    <- Log[F].info(s"Looking up sponsor (replay) ...")
        cost <- runtime.cost.get
        _    <- runtime.cost.set(Cost.UNSAFE_MAX)

        checkpoint <- runtime.createSoftCheckpoint
        _          <- runtime.inj(toPar(send))(rand.splitByte(BlockRandomSeed.PreChargeSplitIndex))
        chValues   <- runtime.getData(ackChannel) //chValues.isEmpty is true for some reason
        _          <- runtime.revertToSoftCheckpoint(checkpoint)

        _ <- runtime.cost.set(cost)

        ret = chValues.flatMap(
          d => d.a.pars
        )

        result = ret match {
          case Seq(RhoType.RhoNumber(x)) => x
          case _                         => 0
        }
        _ <- Log[F].info(s"Sponsored phlo: ${result}")

      } yield result

    val refT = Ref.of(Set[Par]()).liftEitherT[ReplayFailure]
    refT flatMap { mergeable =>
      val expectedFailure = processedDeploy.systemDeployError
      val preChargeF =
        Span[F].mark("precharge-started").liftEitherT[ReplayFailure] *>
          EitherT
            .liftF(getSponsorPhlo)
            .flatMap(
              sponsoredPhlo =>
                replaySystemDeployInternal(
                  new PreChargeDeploy(
                    if (sponsoredPhlo == 0) processedDeploy.deploy.data.totalPhloCharge
                    else sponsoredPhlo.min(processedDeploy.deploy.data.totalPhloCharge),
                    if (sponsoredPhlo == 0) processedDeploy.deploy.pk
                    else
                      PublicKey(
                        fromHex(
                          processedDeploy.deploy.data.sponsorPubKey
                        ).get.toArray
                      ),
                    rand.splitByte(BlockRandomSeed.PreChargeSplitIndex)
                  ),
                  expectedFailure
                )
            )
            .semiflatMap {
              case (_, evalResult) =>
                for {
                  _ <- Span[F].mark("precharge-done")
                  _ <- runtime.createSoftCheckpoint

                  // Collect Pre-charge mergeable channels
                  _ <- mergeable.update(_ ++ evalResult.mergeable).whenA(evalResult.succeeded)
                } yield ()
            }

      val refundF =
        Span[F].mark("refund-started").liftEitherT[ReplayFailure] *>
          replaySystemDeployInternal(
            new RefundDeploy(
              processedDeploy.refundAmount,
              rand.splitByte(BlockRandomSeed.RefundSplitIndex)
            ),
            None
          ).semiflatTap {
            case (_, evalResult) =>
              for {
                _ <- Span[F].mark("refund-done")
                _ <- runtime.createSoftCheckpoint

                // Collect Refund mergeable channels
                _ <- mergeable.update(_ ++ evalResult.mergeable).whenA(evalResult.succeeded)
              } yield ()

          }

      val deployEvaluator = EitherT
        .liftF {
          runtime.withSoftTransaction {
            for {
              result <- runtime.evaluate(
                         processedDeploy.deploy,
                         rand.splitByte(BlockRandomSeed.UserDeploySplitIndex)
                       )

              logErrors = printDeployErrors(processedDeploy.deploy.sig, result.errors)
              /* Since the state of `replaySpace` is reset on each invocation of `replayComputeState`,
              and `ReplayFailure`s mean that block processing is cancelled upstream, we only need to
              reset state if the replay effects of valid deploys need to be discarded. */
              _ <- logErrors.whenA(result.failed)

              // Collect user deploy mergeable channels
              _ <- mergeable.update(_ ++ result.mergeable).whenA(result.succeeded)
            } yield (result, result.succeeded)
          }
        }
        .ensureOr { result =>
          // Regardless of success or failure, verify that deploy status' match.
          ReplayFailure.replayStatusMismatch(processedDeploy.isFailed, result.failed)
        }(result => processedDeploy.isFailed == result.failed)
        .ensureOr { result =>
          // Verify evaluation costs match.
          ReplayFailure.replayCostMismatch(processedDeploy.cost.cost, result.cost.value)
        }(result => processedDeploy.cost.cost == result.cost.value)

      def evaluatorT: EitherT[F, ReplayFailure, Boolean] =
        if (withCostAccounting) {
          preChargeF
            .flatMap { _ =>
              if (expectedFailure.isEmpty)
                deployEvaluator
                  .semiflatMap { evalResult =>
                    for {
                      _ <- Span[F].mark("deploy-eval-done")
                      _ <- runtime.createSoftCheckpoint.whenA(evalResult.succeeded)
                      _ <- Span[F].mark("deploy-done")
                    } yield evalResult.succeeded
                  }
                  .flatTap(_ => refundF)
              else EitherT.rightT(true)
            }
        } else deployEvaluator.map(_.succeeded)

      rigWithCheck(processedDeploy, evaluatorT.map(((), _)).value).semiflatMap { _ =>
        for {
          collected             <- mergeable.get
          mergeableChannelsData <- runtime.getNumberChannelsData(collected)
        } yield mergeableChannelsData
      }
    }
  }

  /* REPLAY System deploy evaluators */

  /**
    * Evaluates System deploy with checkpoint to get final state hash
    */
  def replayBlockSystemDeployDiag(
      processedSystemDeploy: ProcessedSystemDeploy,
      rand: Blake2b512Random
  )(
      implicit s: Sync[F],
      span: Span[F]
  ): F[Either[ReplayFailure, NumberChannelsEndVal]] =
    Span[F].withMarks("replay-system-deploy")(
      replayBlockSystemDeploy(processedSystemDeploy, rand).value
    )

  def replayBlockSystemDeploy(
      processedSysDeploy: ProcessedSystemDeploy,
      rand: Blake2b512Random
  )(implicit s: Sync[F], span: Span[F]): EitherT[F, ReplayFailure, NumberChannelsEndVal] =
    processedSysDeploy.systemDeploy match {
      case SlashSystemDeployData(slashedValidator) =>
        val slashDeploy = {
          SlashDeploy(slashedValidator, rand)
        }
        rigWithCheck(
          processedSysDeploy,
          replaySystemDeployInternal(slashDeploy, none).semiflatMap {
            case (_, er) =>
              runtime.createSoftCheckpoint.whenA(er.succeeded) *>
                runtime.getNumberChannelsData(er.mergeable).map((_, er))
          }
        ).map(_._1)
      case CloseBlockSystemDeployData =>
        val closeBlockDeploy = CloseBlockDeploy(rand)
        rigWithCheck(
          processedSysDeploy,
          replaySystemDeployInternal(closeBlockDeploy, none).semiflatMap {
            case (_, er) =>
              runtime.createSoftCheckpoint.whenA(er.succeeded) *>
                runtime.getNumberChannelsData(er.mergeable).map((_, er))
          }
        ).map(_._1)
      case Empty =>
        EitherT.leftT(ReplayFailure.internalError(new Exception("Expected system deploy")))
    }

  def replaySystemDeployInternal[S <: SystemDeploy](
      systemDeploy: S,
      expectedFailureMsg: Option[String]
  )(implicit sync: Sync[F], span: Span[F]): EitherT[F, ReplayFailure, SysEvalResult[S]] = {
    // Evaluate system deploy
    val fe = runtime
      .evalSystemDeploy(systemDeploy)
      .map {
        // Compare evaluation from play and replay, successful or failed
        case (result, evalRes) =>
          (expectedFailureMsg, result) match {
            // Valid replay
            case (None, r @ Right(_)) =>
              // Replayed successful execution
              (r, evalRes).asRight
            case (Some(expectedError), r @ Left(SystemDeployUserError(actualError)))
                if expectedError == actualError =>
              // Replayed failed execution
              (r, evalRes).asRight

            // Invalid replay
            case (Some(expectedError), Left(SystemDeployUserError(actualError))) =>
              // Error messages different
              ReplayFailure.systemDeployErrorMismatch(expectedError, actualError).asLeft
            case (Some(expectedError @ _), Right(result @ _)) =>
              // Error expected, replay successful
              ReplayFailure.replayStatusMismatch(initialFailed = true, replayFailed = false).asLeft
            case (None, Left(actualError @ _)) =>
              // No error expected, replay failed
              ReplayFailure.replayStatusMismatch(initialFailed = false, replayFailed = true).asLeft
          }
      }
    EitherT(fe)
  }

  /* Helper functions */

  def rigWithCheck[A](
      processedDeploy: ProcessedDeploy,
      action: F[Either[ReplayFailure, (A, Boolean)]]
  )(implicit s: Sync[F]): EitherT[F, ReplayFailure, (A, Boolean)] =
    EitherT(rig(processedDeploy) *> action) flatMap {
      case r @ (_, evalRes) => checkReplayDataWithFix(evalRes).as(r)
    }

  def rigWithCheck[A](
      processedSystemDeploy: ProcessedSystemDeploy,
      action: EitherT[F, ReplayFailure, (A, EvaluateResult)]
  )(implicit s: Sync[F]): EitherT[F, ReplayFailure, (A, EvaluateResult)] =
    rig(processedSystemDeploy).liftEitherT[ReplayFailure] *> action flatMap {
      case r @ (_, evalRes) => checkReplayDataWithFix(evalRes.succeeded).as(r)
    }

  def rig(processedDeploy: ProcessedDeploy): F[Unit] =
    runtime.rig(processedDeploy.deployLog.map(EventConverter.toRspaceEvent))

  def rig(processedSystemDeploy: ProcessedSystemDeploy): F[Unit] =
    runtime.rig(processedSystemDeploy.eventList.map(EventConverter.toRspaceEvent))

  def checkReplayDataWithFix(
      evalSuccessful: Boolean
  )(implicit s: Sync[F]): EitherT[F, ReplayFailure, Unit] =
    runtime.checkReplayData.attemptT
      .leftMap {
        case replayException: ReplayException =>
          ReplayFailure.unusedCOMMEvent(replayException)
        case throwable => ReplayFailure.internalError(throwable)
      }
      .leftFlatMap {
        case UnusedCOMMEvent(_) if !evalSuccessful =>
          // TODO: temp fix for replay error mismatch
          // https://rchain.atlassian.net/browse/RHONIX-3505
          EitherT.rightT[F, ReplayFailure](())
        case ex: ReplayFailure => EitherT.leftT[F, Unit](ex)
      }

}
