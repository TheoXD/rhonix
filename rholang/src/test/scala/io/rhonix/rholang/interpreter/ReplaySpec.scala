package io.rhonix.rholang.interpreter

import cats.syntax.all._
import io.rhonix.crypto.hash.Blake2b512Random
import io.rhonix.metrics
import io.rhonix.metrics.{Metrics, NoopSpan, Span}
import io.rhonix.rholang.Resources
import io.rhonix.rholang.interpreter.accounting.Cost
import io.rhonix.rspace.SoftCheckpoint
import io.rhonix.shared.Log
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration._

class ReplaySpec extends AnyFlatSpec with Matchers {

  // TODO: these tests are temporary and specific to bugs found in replay.
  // Testing execution for many iteration doesn't make sense.
  // In tests, nondeterministic execution of Par in tuple space should be replaced with deterministic version
  // so that we can test tuple space access in all possible states (and not guess and wait).

  // TODO: fuzzer test now creates complete on-disk tuple space for each execution, this can be replaced
  //  with in-memory version used here.
  // https://github.com/rhonixlabs/rhonix/blob/1f9554f68a/rholang/src/test/scala/io/rhonix/rholang/interpreter/accounting/CostAccountingSpec.scala#L231

  // Temporarily disabled tests with joins on the same channels
  // https://rchain.atlassian.net/browse/RCHAIN-4032

  "multi joins (2/2)" should "execute successfully in replay" ignore {
    val term =
      """
        |new x in {
        |  x!() | for(<- x & <- x) { 0 } | x!()
        |}
        |""".stripMargin
    testRholangTerm(term, 500, 30.seconds)
  }

  "multi joins (4/2)" should "execute successfully in replay" ignore {
    val term =
      """
        |new x in {
        |  x!() | x!() | for(<- x & <- x) { 0 } | x!() | x!()
        |}
        |""".stripMargin
    testRholangTerm(term, 500, 30.seconds)
  }

  def testRholangTerm(term: String, iterations: Int, timeout: Duration) =
    withRSpaceAndRuntime {
      case (runtime, replayRuntime) =>
        for (i <- 1 to iterations) {
          val (playRes, replayRes) =
            evaluateWithRuntime(runtime, replayRuntime)(term, Cost(Integer.MAX_VALUE))
              .onError {
                case _: Throwable =>
                  println(s"Test retry count: $i").pure[Task]
              }
              .runSyncUnsafe(1.seconds)

          assert(playRes.errors.isEmpty)
          assert(replayRes.errors.isEmpty)
        }
        ().pure[Task]
    }.runSyncUnsafe(timeout)

  def evaluateWithRuntime(
      runtime: RhoRuntime[Task],
      replayRuntime: ReplayRhoRuntime[Task]
  )(term: String, initialPhlo: Cost) = {
    implicit def rand: Blake2b512Random = Blake2b512Random(Array.empty[Byte])
    for {
      // Save revert checkpoints
      startState       <- runtime.createSoftCheckpoint
      startReplayState <- replayRuntime.createSoftCheckpoint

      // Execute play
      playResult <- runtime.evaluate(term, initialPhlo, Map.empty, rand)

      // Create play snapshot (diff)
      playSnapshot              <- runtime.createSoftCheckpoint
      SoftCheckpoint(_, log, _) = playSnapshot

      // Prepare replay with events log from play
      _ <- replayRuntime.rig(log)

      // Execute replay
      replayResult <- replayRuntime
                       .evaluate(term, initialPhlo, Map.empty, rand)
                       .onError {
                         case _: Throwable =>
                           println(s"Executed term: $term")
                           println(s"Event log: $log").pure[Task]
                       }
      _ <- replayRuntime.checkReplayData.onError {
            case _: Throwable =>
              println(s"Executed term: $term")
              println(s"Event log: $log")
              println(s"Replay result: $replayResult").pure[Task]
          }

      // Revert all changes / reset to initial state
      _ <- runtime.revertToSoftCheckpoint(startState)
      _ <- replayRuntime.revertToSoftCheckpoint(startReplayState)
    } yield (playResult, replayResult)
  }

  def withRSpaceAndRuntime(op: (RhoRuntime[Task], ReplayRhoRuntime[Task]) => Task[Unit]) = {
    implicit val logF: Log[Task]           = new Log.NOPLog[Task]
    implicit val metricsEff: Metrics[Task] = new metrics.Metrics.MetricsNOP[Task]
    implicit val noopSpan: Span[Task]      = NoopSpan[Task]()

    val resources = for {
      res <- Resources.mkRuntimes[Task]("cost-accounting-spec-")
    } yield res

    resources.use {
      case (runtime, replayRuntime, _) =>
        // Execute operation
        op(runtime, replayRuntime)
    }
  }

}
