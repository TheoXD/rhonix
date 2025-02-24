package io.rhonix.rholang.interpreter.storage

import cats.effect.Sync
import com.google.protobuf.ByteString
import io.rhonix.crypto.hash.Blake2b512Random
import io.rhonix.metrics
import io.rhonix.metrics.{Metrics, NoopSpan}
import io.rhonix.models.Expr.ExprInstance.GInt
import io.rhonix.models.TaggedContinuation.TaggedCont.ParBody
import io.rhonix.models.Var.VarInstance.FreeVar
import io.rhonix.models._
import io.rhonix.models.rholang.implicits._
import io.rhonix.rholang.Resources.mkRhoISpace
import io.rhonix.rholang.interpreter.RhoRuntime.{RhoISpace, RhoTuplespace}
import io.rhonix.rholang.interpreter.accounting
import io.rhonix.rholang.interpreter.accounting.{CostAccounting, _}
import io.rhonix.rholang.interpreter.errors.OutOfPhlogistonsError
import io.rhonix.rholang.interpreter.storage.ChargingRSpaceTest.{ChargingRSpace, _}
import io.rhonix.shared.Log
import io.rhonix.shared.scalatestcontrib._
import io.rhonix.store.InMemoryStoreManager
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global
import org.scalactic.TripleEqualsSupport
import org.scalatest.Outcome
import org.scalatest.flatspec.FixtureAnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration._

class ChargingRSpaceTest extends FixtureAnyFlatSpec with TripleEqualsSupport with Matchers {

  behavior of "ChargingRSpace"

  val channels                = channelsN(1)
  val patterns                = patternsN(1)
  val cont                    = continuation()
  val consumeStorageCost      = accounting.storageCostConsume(channels, patterns, cont)
  val channel                 = channels.head
  val data                    = NilPar
  val produceStorageCost      = accounting.storageCostProduce(channel, data)
  val produceEventStorageCost = accounting.eventStorageCost(1)
  val consumeEventStorageCost = accounting.eventStorageCost(channels.size)
  val commEventStorageCost    = accounting.commEventStorageCost(channels.size)

  it should "charge for storing data in tuplespace" in { fixture =>
    val TestFixture(chargingRSpace, cost) = fixture
    val minimumPhlos                      = consumeStorageCost + consumeEventStorageCost

    val test = for {
      _ <- cost.set(minimumPhlos)
      _ <- chargingRSpace.consume(channels, patterns, cont, false)
      _ <- cost.get shouldBeF Cost(0)
    } yield ()

    test.runSyncUnsafe(1.second)
  }

  it should "refund if data doesn't stay in tuplespace" in { fixture =>
    val TestFixture(chargingRSpace, cost) = fixture
    val minimumPhlos =
      produceStorageCost + produceEventStorageCost + consumeStorageCost + consumeEventStorageCost + commEventStorageCost

    val test = for {
      _ <- cost.set(minimumPhlos)
      _ <- chargingRSpace.produce(channels.head, data, false)
      _ <- cost.get shouldBeF (minimumPhlos - produceStorageCost - produceEventStorageCost)
      _ <- chargingRSpace.consume(channels, patterns, cont, false)
      _ <- cost.get shouldBeF (consumeStorageCost + produceStorageCost)
    } yield ()

    test.runSyncUnsafe(1.second)
  }

  it should "fail with OutOfPhloError when deploy runs out of it" in { fixture =>
    val TestFixture(chargingRSpace, cost) = fixture

    val test = for {
      _ <- cost.set(produceStorageCost - Cost(1))
      _ <- chargingRSpace.produce(channel, data, false)
    } yield ()

    val outOfPhloTest = test.attempt.runSyncUnsafe(1.second)
    assert(outOfPhloTest === Left(OutOfPhlogistonsError))

    val costTest = cost.get.runSyncUnsafe(1.second)
    assert(costTest.value === -1)
  }

  it should "charge COMM on a join properly when parts of the join are deployed separately" in {
    // first deploy:
    // for(x <- @x & y <- @y) { P }
    // second deploy:
    // @x!(data)
    // third deploy:
    // @y!(data)
    // last deployment should be refunded with the cost of storing two previous deployments
    fixture =>
      val TestFixture(chargingRSpace, cost) = fixture
      val channels                          = channelsN(2)
      val patterns                          = patternsN(2)
      val firstProdCost                     = accounting.storageCostProduce(channels(0), data)
      val secondProdCost                    = accounting.storageCostProduce(channels(1), data)
      val joinCost                          = accounting.storageCostConsume(channels, patterns, cont)
      val consumeEventStorageCost           = accounting.eventStorageCost(channels.size)
      val commEventStorageCost              = accounting.commEventStorageCost(channels.size)

      val minimumPhlos =
        firstProdCost + produceEventStorageCost +
          secondProdCost + produceEventStorageCost +
          joinCost + consumeEventStorageCost + commEventStorageCost

      val test = for {
        _                   <- cost.set(minimumPhlos)
        _                   <- chargingRSpace.consume(channels, patterns, cont, false)
        phlosAfterConsume   <- cost.get
        _                   = phlosAfterConsume shouldBe (minimumPhlos - consumeEventStorageCost - joinCost)
        _                   <- chargingRSpace.produce(channels(0), data, false)
        phlosAfterFirstSend <- cost.get
        _                   = phlosAfterFirstSend shouldBe (phlosAfterConsume - firstProdCost - produceEventStorageCost)
        _                   <- chargingRSpace.produce(channels(1), data, false)
        phlosLeft           <- cost.get
        _                   = phlosLeft.value shouldBe (firstProdCost + secondProdCost + joinCost).value
      } yield ()

      test.runSyncUnsafe(1.second)
  }

  it should "not charge for storage if linear terms create a COMM" in { fixture =>
    // for(x <- @x) | @x!(10)
    // we should not charge for storing any of the terms
    val TestFixture(chargingRSpace, cost) = fixture

    val data               = ListParWithRandom().withPars(Vector(GInt(1)))
    val produceStorageCost = storageCostProduce(channel, data)

    val initPhlos =
      consumeStorageCost + consumeEventStorageCost + produceStorageCost + produceEventStorageCost + commEventStorageCost

    val test = for {
      _ <- cost.set(initPhlos)
      _ <- chargingRSpace.consume(channels, patterns, cont, false)
      _ <- chargingRSpace.produce(channel, data, false)
      _ <- cost.get shouldBeF (consumeStorageCost + produceStorageCost)
    } yield ()

    test.runSyncUnsafe(1.second)
  }

  it should "charge for storing persistent produce that create a COMM" in { fixture =>
    // for(x <- @x) { P } | @x!!(100)
    // we should charge for storing non-linear produce
    val TestFixture(chargingRSpace, cost) = fixture
    val pattern                           = BindPattern(Vector(EVar(FreeVar(0))))

    val data        = ListParWithRandom().withPars(Vector(GInt(1)))
    val produceCost = storageCostProduce(channel, data)

    val initPhlos = Cost(1000)

    val test = for {
      _ <- cost.set(initPhlos)
      _ <- chargingRSpace.produce(channel, data, true)
      _ <- chargingRSpace.consume(channels, List(pattern), cont, false)
      _ <- cost.get shouldBeF (
            initPhlos - produceCost - produceEventStorageCost - consumeEventStorageCost - commEventStorageCost
          )
    } yield ()

    test.runSyncUnsafe(1.second)
  }

  it should "charge for storing persistent consume that create a COMM" in { fixture =>
    // for(x <= @x) { P } | @x!(100)
    // we should charge for storing non-linear continuation
    val TestFixture(chargingRSpace, cost) = fixture

    val data = ListParWithRandom().withPars(Vector(GInt(1)))

    val initPhlos = Cost(1000)

    val test = for {
      _ <- cost.set(initPhlos)
      _ <- chargingRSpace.consume(channels, patterns, cont, true)
      _ <- chargingRSpace.produce(channel, data, false)
      _ <- cost.get shouldBeF (
            initPhlos - consumeStorageCost - produceEventStorageCost - consumeEventStorageCost - commEventStorageCost
          )
    } yield ()

    test.runSyncUnsafe(1.second)
  }

  it should "refund for linear data in join" in { fixture =>
    // idea for the test is that we have persistent and non persistent produce in first deploy:
    // @"x"!!(1) | @"y"!(10)
    // and consume on joined channels in another:
    // for(x <- @"x" & y <- @"y") { … }
    // In this case we shouldn't charge for storing consume and refund for removing produce on @"y"

    val TestFixture(chargingRSpace, cost) = fixture
    val channels                          = channelsN(2)
    val patterns                          = patternsN(2)
    val cont                              = continuation()

    val dataX = ListParWithRandom().withPars(Vector(GInt(1)))
    val dataY = ListParWithRandom().withPars(Vector(GInt(10)))

    val produceYCost            = accounting.storageCostProduce(channels(1), dataY)
    val consumeEventStorageCost = accounting.eventStorageCost(channels.size)
    val commEventStorageCost    = accounting.commEventStorageCost(channels.size)

    val initPhlos = Cost(1000)

    val test = for {
      _ <- cost.set(initPhlos)
      _ <- chargingRSpace.produce(channels(0), dataX, persist = true)
      _ <- chargingRSpace.produce(channels(1), dataY, persist = false)
      _ <- cost.set(initPhlos)
      _ <- chargingRSpace.consume(channels, patterns, cont, false)
      _ <- cost.get shouldBeF (initPhlos + produceYCost - consumeEventStorageCost - commEventStorageCost)
    } yield ()

    test.runSyncUnsafe(1.second)
  }

  it should "refund for removing consume" in { fixture =>
    // first deploy:
    // for(x <- @x) { P }
    // second deploy:
    // @x!(100)
    // we should refund for removing continuation from tuplespace
    val TestFixture(chargingRSpace, cost) = fixture

    val initPhlos = Cost(1000)

    val test = for {
      _ <- cost.set(initPhlos)
      _ <- chargingRSpace.consume(channels, patterns, cont, false)
      _ <- cost.set(initPhlos)
      _ <- chargingRSpace.produce(channel, data, persist = false)
      _ <- cost.get shouldBeF (initPhlos + consumeStorageCost - produceEventStorageCost - commEventStorageCost)
    } yield ()

    test.runSyncUnsafe(1.second)
  }

  it should "refund for removing produce" in { fixture =>
    // first deploy:
    // @x!(100)
    // second deploy:
    // for(x <- @x) { P }
    // we should refund for removing @x!(100) from tuplespace
    val TestFixture(chargingRSpace, cost) = fixture

    val data        = ListParWithRandom().withPars(Vector(GInt(1)))
    val produceCost = accounting.storageCostProduce(channel, data)

    val initPhlos = Cost(1000)

    val test = for {
      _ <- cost.set(initPhlos)
      _ <- chargingRSpace.produce(channel, data, persist = false)
      _ <- cost.set(initPhlos)
      _ <- chargingRSpace.consume(channels, patterns, cont, false)
      _ <- cost.get shouldBeF (initPhlos + produceCost - consumeEventStorageCost - commEventStorageCost)
    } yield ()

    test.runSyncUnsafe(1.second)
  }

  it should "refund for clearing tuplespace" in { fixture =>
    // first deploy:
    // @x!(100) | @y!(10) | for(x <- @x & y <- @y & z <- @z) { P }
    // second deploy:
    // @z!(1)
    // since second deploy triggers continuation we should refund with the cost of storing first deploy
    val TestFixture(chargingRSpace, cost) = fixture
    val List(x, y, z)                     = channelsN(3)
    val patterns                          = patternsN(3)
    val cont                              = continuation()

    val dataX                = ListParWithRandom().withPars(Vector(GInt(1)))
    val dataY                = ListParWithRandom().withPars(Vector(GInt(10)))
    val dataZ                = ListParWithRandom().withPars(Vector(GInt(100)))
    val produceXCost         = accounting.storageCostProduce(x, dataX)
    val produceYCost         = accounting.storageCostProduce(y, dataY)
    val consumeCost          = accounting.storageCostConsume(List(x, y, z), patterns, cont)
    val commEventStorageCost = accounting.commEventStorageCost(3)

    val initPhlos = Cost(10000)

    val test = for {
      _ <- cost.set(initPhlos)
      _ <- chargingRSpace.produce(x, dataX, false)
      _ <- chargingRSpace.produce(y, dataY, false)
      _ <- chargingRSpace.consume(List(x, y, z), patterns, cont, false)
      _ <- cost.set(initPhlos)
      _ <- chargingRSpace.produce(z, dataZ, false)
      _ <- cost.get shouldBeF (
            initPhlos + produceXCost + produceYCost + consumeCost - produceEventStorageCost - commEventStorageCost
          )
    } yield ()

    test.runSyncUnsafe(5.seconds)
  }

  override type FixtureParam = TestFixture

  protected override def withFixture(test: OneArgTest): Outcome = {
    val cost: _cost[Task] = CostAccounting.emptyCost[Task].runSyncUnsafe(1.second)
    implicit val span     = NoopSpan[Task]
    implicit val kvm      = InMemoryStoreManager[Task]

    def mkChargingRspace(rhoISpace: RhoISpace[Task]): Task[ChargingRSpace] = {
      val s = implicitly[Sync[Task]]
      Task.delay(ChargingRSpace.chargingRSpace(rhoISpace)(s, span, cost))
    }

    mkRhoISpace[Task]
      .flatMap(mkChargingRspace)
      .flatMap(chargingRSpace => Task.delay { test(TestFixture(chargingRSpace, cost)) })
      .runSyncUnsafe(10.seconds)
  }

}

object ChargingRSpaceTest {
  type ChargingRSpace = RhoTuplespace[Task]
  final case class TestFixture(chargingRSpace: ChargingRSpace, cost: _cost[Task])

  val NilPar = ListParWithRandom().withPars(Seq(Par()))

  def channelsN(n: Int): List[Par] =
    (1 to n).map(x => byteName(x.toByte)).toList

  private def byteName(b: Byte): Par = GPrivate(ByteString.copyFrom(Array[Byte](b)))

  def patternsN(n: Int): List[BindPattern] =
    (1 to n)
      .map(
        _ => BindPattern(Vector(EVar(Var(FreeVar(0)))), freeCount = 1)
      )
      .toList

  def continuation(
      par: Par = Par().withExprs(Seq(GInt(1))),
      r: Blake2b512Random = Blake2b512Random.defaultRandom
  ): TaggedContinuation =
    TaggedContinuation(ParBody(ParWithRandom(par, r)))

  implicit val logF: Log[Task]            = new Log.NOPLog[Task]
  implicit val noopMetrics: Metrics[Task] = new metrics.Metrics.MetricsNOP[Task]
  implicit val ms: Metrics.Source         = Metrics.BaseSource
}
