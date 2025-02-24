package io.rhonix.rholang.interpreter

import cats.Parallel
import cats.effect.Sync
import cats.effect.concurrent.Ref
import io.rhonix.crypto.hash.Blake2b512Random
import io.rhonix.metrics.{Metrics, Span}
import io.rhonix.models.Expr.ExprInstance.{EVarBody, GString}
import io.rhonix.models.Var.VarInstance.FreeVar
import io.rhonix.models._
import io.rhonix.models.rholang.RhoType.RhoName
import io.rhonix.models.rholang.implicits._
import io.rhonix.rholang.Resources.mkRhoISpace
import io.rhonix.rholang.interpreter.RhoRuntime.{RhoISpace, RhoTuplespace}
import io.rhonix.rholang.interpreter.accounting._
import io.rhonix.rholang.interpreter.errors.OutOfPhlogistonsError
import io.rhonix.rholang.interpreter.storage._
import io.rhonix.rspace._
import io.rhonix.rspace.internal.{Datum, Row}
import io.rhonix.shared.Log
import io.rhonix.store.InMemoryStoreManager
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global
import org.scalactic.TripleEqualsSupport
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration._

class CostAccountingReducerTest extends AnyFlatSpec with Matchers with TripleEqualsSupport {

  implicit val noopSpan: Span[Task]   = Span.noop
  implicit val metrics: Metrics[Task] = new Metrics.MetricsNOP[Task]
  implicit val ms: Metrics.Source     = Metrics.BaseSource

  behavior of "Cost accounting in Reducer"

  def createDispatcher[M[_]: Sync: Parallel: _cost](
      tuplespace: RhoTuplespace[M],
      dispatchTable: => Map[Long, Seq[ListParWithRandom] => M[Unit]],
      urnMap: Map[String, Par]
  ): (Dispatch[M, ListParWithRandom, TaggedContinuation], Reduce[M]) = {
    val emptyMergeableRef = Ref.unsafe[M, Set[Par]](Set.empty)
    val dummyMergeableTag = RhoName(Array[Byte]())
    RholangAndScalaDispatcher(
      tuplespace,
      dispatchTable,
      urnMap,
      emptyMergeableRef,
      dummyMergeableTag
    )
  }

  it should "charge for the successful substitution" in {
    val term: Expr => Par = expr => Par(bundles = Seq(Bundle(Par(exprs = Seq(expr)))))
    val substTerm         = term(Expr(GString("1")))
    val termCost          = Chargeable[Par].cost(substTerm)
    val initCost          = Cost(1000)
    (for {
      cost <- CostAccounting.initialCost[Task](initCost)
      res <- {
        implicit val c = cost
        Substitute.charge(Task.now(substTerm), Cost(10000)).attempt
      }
      _         = assert(res === Right(substTerm))
      finalCost <- cost.get
      _         = assert(finalCost === (initCost - Cost(termCost)))
    } yield ()).runSyncUnsafe(5.seconds)
  }

  it should "charge for failed substitution" in {
    val term: Expr => Par = expr => Par(bundles = Seq(Bundle(Par(exprs = Seq(expr)))))
    val varTerm           = term(Expr(EVarBody(EVar(Var(FreeVar(0))))))
    val originalTermCost  = Chargeable[Par].cost(varTerm)
    val initCost          = Cost(1000)

    (for {
      cost <- CostAccounting.initialCost[Task](initCost)
      res <- {
        implicit val c = cost
        Substitute
          .charge(Task.raiseError[Par](new RuntimeException("")), Cost(originalTermCost))
          .attempt
      }
      _         = assert(res.isLeft)
      finalCost <- cost.get
      _         = assert(finalCost === (initCost - Cost(originalTermCost)))
    } yield ()).runSyncUnsafe(5.seconds)
  }

  it should "stop if OutOfPhloError is returned from RSpace" in {

    val iSpace = new ISpaceStub[
      Task,
      Par,
      BindPattern,
      ListParWithRandom,
      TaggedContinuation
    ] {
      override def produce(
          channel: Par,
          data: ListParWithRandom,
          persist: Boolean
      ): Task[
        Option[
          (ContResult[Par, BindPattern, TaggedContinuation], Seq[Result[Par, ListParWithRandom]])
        ]
      ] =
        Task.raiseError[Option[
          (ContResult[Par, BindPattern, TaggedContinuation], Seq[Result[Par, ListParWithRandom]])
        ]](OutOfPhlogistonsError)
    }
    implicit val rand        = Blake2b512Random.defaultRandom
    implicit val cost        = CostAccounting.initialCost[Task](Cost(1000)).runSyncUnsafe(1.second)
    val (_, chargingReducer) = createDispatcher(iSpace, Map.empty, Map.empty)
    val send                 = Send(Par(exprs = Seq(GString("x"))), Seq(Par()))
    val test                 = chargingReducer.inj(send).attempt.runSyncUnsafe(1.second)
    assert(test === Left(OutOfPhlogistonsError))
  }

  it should "stop interpreter threads as soon as deploy runs out of phlo" ignore {
    // Given
    // new x in { @x!("a") | @x!("b") }
    // and not enough phlos to reduce successfully
    // only one of the branches we should be persisted in the tuplespace

    val channel: Par = GPrivateBuilder("x")

    val a: Par = GString("a")
    val b: Par = GString("b")
    val program =
      Par(sends = Seq(Send(channel, Seq(a)), Send(channel, Seq(b))))

    implicit val rand            = Blake2b512Random(Array.empty[Byte])
    implicit val logF: Log[Task] = Log.log[Task]
    implicit val kvm             = InMemoryStoreManager[Task]

    def testImplementation(pureRSpace: RhoISpace[Task]): Task[
      (
          Either[Throwable, Unit],
          Map[Seq[Par], Row[BindPattern, ListParWithRandom, TaggedContinuation]]
      )
    ] = {

      implicit val cost = CostAccounting.emptyCost[Task].runSyncUnsafe(1.second)

      lazy val (_, reducer) =
        createDispatcher(pureRSpace, Map.empty, Map.empty)

      def plainSendCost(p: Par): Cost = {
        val storageCost      = accounting.storageCostProduce(channel, ListParWithRandom(Seq(p)))
        val substitutionCost = Cost(Chargeable[Par].cost(channel)) + Cost(Chargeable[Par].cost(p))
        substitutionCost + storageCost + SEND_EVAL_COST
      }
      val sendACost = plainSendCost(a)
      val sendBCost = plainSendCost(b)

      val initPhlos = sendACost + sendBCost - SEND_EVAL_COST - Cost(1)

      for {
        _           <- cost.set(initPhlos)
        result      <- reducer.inj(program).attempt
        mappedSpace <- pureRSpace.toMap
      } yield (result, mappedSpace)
    }

    def data(p: Par, rand: Blake2b512Random) = Row(
      List(Datum.create(channel, ListParWithRandom(Seq(p), rand), false)),
      List()
    )

    def stored(
        map: Map[Seq[Par], Row[BindPattern, ListParWithRandom, TaggedContinuation]],
        p: Par,
        rand: Blake2b512Random
    ): Boolean =
      map.get(List(channel)) === Some(data(p, rand))

    (for {
      res           <- mkRhoISpace[Task].flatMap(testImplementation)
      (result, map) = res
      _             = assert(result === Left(OutOfPhlogistonsError))
      _             = assert(stored(map, a, rand.splitByte(0)) || stored(map, b, rand.splitByte(1)))
    } yield ()).runSyncUnsafe(5.seconds)

  }
}
