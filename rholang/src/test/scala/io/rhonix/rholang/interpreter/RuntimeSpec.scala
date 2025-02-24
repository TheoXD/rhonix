package io.rhonix.rholang.interpreter

import io.rhonix.metrics
import io.rhonix.metrics.{Metrics, NoopSpan, Span}
import io.rhonix.rholang.Resources.mkRuntime
import io.rhonix.rholang.syntax._
import io.rhonix.shared.Log
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration._

class RuntimeSpec extends AnyFlatSpec with Matchers {
  private val tmpPrefix                   = "rspace-store-"
  private val maxDuration                 = 5.seconds
  implicit val logF: Log[Task]            = Log.log[Task]
  implicit val noopMetrics: Metrics[Task] = new metrics.Metrics.MetricsNOP[Task]
  implicit val noopSpan: Span[Task]       = NoopSpan[Task]()

  private val channelReadOnlyError = "ReduceError: Trying to read from non-readable channel."

  "rho:io:stdout" should "not get intercepted" in {
    checkError("""new s(`rho:io:stdout`) in { for(x <- s) { Nil } }""", channelReadOnlyError)
  }

  "rho:io:stdoutAck" should "not get intercepted" in {
    checkError("""new s(`rho:io:stdoutAck`) in { for(x <- s) { Nil } }""", channelReadOnlyError)
  }

  "rho:io:stderr" should "not get intercepted" in {
    checkError("""new s(`rho:io:stderr`) in { for(x <- s) { Nil } }""", channelReadOnlyError)
  }

  "rho:io:stderrAck" should "not get intercepted" in {
    checkError("""new s(`rho:io:stderrAck`) in { for(x <- s) { Nil } }""", channelReadOnlyError)
  }

  "rho:registry:lookup" should "not get intercepted" in {
    checkError("""new l(`rho:registry:lookup`) in { for(x <- l) { Nil } }""", channelReadOnlyError)
  }

  "rho:registry:insertArbitrary" should "not get intercepted" in {
    checkError(
      """new i(`rho:registry:insertArbitrary`) in { for(x <- i) { Nil } }""",
      channelReadOnlyError
    )
  }

  private def checkError(rho: String, error: String): Unit =
    assert(execute(rho).errors.nonEmpty, s"Expected $rho to fail - it didn't.")

  private def execute(source: String): EvaluateResult =
    mkRuntime[Task](tmpPrefix)
      .use { runtime =>
        runtime.evaluate(source)
      }
      .runSyncUnsafe(maxDuration)
}
