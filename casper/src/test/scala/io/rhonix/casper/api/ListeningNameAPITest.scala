package io.rhonix.casper.api

import cats.Applicative
import cats.syntax.all._
import io.rhonix.blockstorage.BlockStore.BlockStore
import io.rhonix.blockstorage.dag.{BlockDagStorage, DagMessageState, DagRepresentation, Message}
import io.rhonix.casper.ValidatorIdentity
import io.rhonix.casper.helper.BlockApiFixture
import io.rhonix.casper.protocol.{BlockMessage, DataWithBlockInfo, ProcessedDeploy, ProduceEvent}
import io.rhonix.casper.rholang.RuntimeManager
import io.rhonix.casper.util.ConstructDeploy
import io.rhonix.casper.util.GenesisBuilder.randomValidatorKeyPairs
import io.rhonix.crypto.hash.Blake2b256
import io.rhonix.metrics.Span
import io.rhonix.models.BlockHash.BlockHash
import io.rhonix.models.Expr.ExprInstance.GInt
import io.rhonix.models.Validator.Validator
import io.rhonix.models._
import io.rhonix.models.blockImplicits.getRandomBlock
import io.rhonix.models.syntax._
import io.rhonix.rspace.hashing.Blake2b256Hash
import io.rhonix.shared.Log
import monix.eval.Task
import monix.testing.scalatest.MonixTaskTest
import org.mockito.cats.IdiomaticMockitoCats
import org.mockito.{ArgumentMatchersSugar, IdiomaticMockito}
import org.scalatest._
import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.collection.immutable.SortedMap

class ListeningNameAPITest
    extends AsyncFlatSpec
    with MonixTaskTest
    with Matchers
    with Inside
    with BlockApiFixture
    with EitherValues
    with IdiomaticMockito
    with IdiomaticMockitoCats
    with ArgumentMatchersSugar {

  private val createValidator = ValidatorIdentity(randomValidatorKeyPairs.take(1).toList.head._1)

  private val listeningTerm = "@{ 3 | 2 | 1 }!(0)"
  private val listeningName = Par().copy(exprs = Seq(Expr(GInt(2)), Expr(GInt(1)), Expr(GInt(3))))
  private val listeningHash = "b87b08f07e4fadbfde88af2ff54a0d9ba58de47a063f798c5a8ce39f8f6892b6"

  private val resultData = Par().copy(exprs = Seq(Expr(GInt(0))))

  private val deploy = ProcessedDeploy
    .empty(ConstructDeploy.sourceDeployNow(listeningTerm, shardId = "root"))
    .copy(
      deployLog = List(
        ProduceEvent(
          Blake2b256Hash.fromHex(listeningHash).toByteString,
          Blake2b256.hash("".getBytes).toByteString,
          persistent = false,
          0
        )
      )
    )

  private val b1 = getRandomBlock(setDeploys = Seq(deploy).some)
  private val b2 = getRandomBlock()
  private val b3 = getRandomBlock()

  "getListeningNameDataResponse" should "return error if depth more than max depth limit" in {
    implicit val (log, sp, rm, bs, bds) = createMocks[Task]
    for {
      blockApi <- createBlockApi[Task]("root", 2, createValidator.some)
      res      <- blockApi.getListeningNameDataResponse(3, listeningName)
    } yield {
      res shouldBe 'left
      res.left.value shouldBe "Your request on getListeningName depth 3 exceed the max limit 2"

      bds.getRepresentation wasCalled once

      verifyNoMoreInteractions(bs)
      verifyNoMoreInteractions(bds)
      verifyNoMoreInteractions(rm)
    }
  }

  it should "return empty result if listening name deeper than expected" in {
    implicit val (log, sp, rm, bs, bds) = createMocks[Task]
    for {
      blockApi <- createBlockApi[Task]("root", 50, createValidator.some)
      res      <- blockApi.getListeningNameDataResponse(1, listeningName)
    } yield {
      res shouldBe 'right
      res.value shouldBe (Seq(), 0)

      bs.get(*) wasCalled twice
      bds.getRepresentation wasCalled once

      verifyNoMoreInteractions(bs)
      verifyNoMoreInteractions(bds)
      verifyNoMoreInteractions(rm)
    }
  }

  it should "return expected result if block falls within the specified depth" in {
    implicit val (log, sp, rm, bs, bds) = createMocks[Task]
    for {
      blockApi <- createBlockApi[Task]("root", 50, createValidator.some)
      res      <- blockApi.getListeningNameDataResponse(2, listeningName)
    } yield {
      res shouldBe 'right
      val (blocks, length) = res.value
      length shouldBe 1
      val (par, block) = (blocks.head.postBlockData.head, blocks.head.block)

      par shouldBe resultData
      block shouldBe BlockApi.getLightBlockInfo(b1)

      rm.getData(*)(*) wasCalled once
      bs.get(*) wasCalled 3.times
      bds.getRepresentation wasCalled once

      verifyNoMoreInteractions(bs)
      verifyNoMoreInteractions(bds)
      verifyNoMoreInteractions(rm)
    }
  }

  it should "return expected result even if depth is greater than possible" in {
    implicit val (log, sp, rm, bs, bds) = createMocks[Task]
    for {
      blockApi <- createBlockApi[Task]("root", 50, createValidator.some)
      res      <- blockApi.getListeningNameDataResponse(10, listeningName)
    } yield {
      res shouldBe 'right
      val (blocks, length) = res.value
      length shouldBe 1
      val (par, block) = (blocks.head.postBlockData.head, blocks.head.block)

      par shouldBe resultData
      block shouldBe BlockApi.getLightBlockInfo(b1)

      rm.getData(*)(*) wasCalled once
      bs.get(*) wasCalled 3.times
      bds.getRepresentation wasCalled once

      verifyNoMoreInteractions(bs)
      verifyNoMoreInteractions(bds)
      verifyNoMoreInteractions(rm)
    }
  }

  def toMessage(m: BlockMessage): Message[BlockHash, Validator] =
    Message[BlockHash, Validator](
      m.blockHash,
      m.blockNumber,
      m.sender,
      m.seqNum,
      m.bonds,
      m.justifications.toSet,
      Set.empty,
      Set(m.blockHash)
    )

  private def createMocks[F[_]: Applicative]
      : (Log[F], Span[F], RuntimeManager[F], BlockStore[F], BlockDagStorage[F]) = {
    val log = mock[Log[F]]
    val sp  = mock[Span[F]]

    val rm = mock[RuntimeManager[F]]
    rm.getData(*)(*) returnsF Seq(resultData)

    val bs = mock[BlockStore[F]]

    bs.get(*) answersF { (keys: Seq[BlockHash]) =>
      Seq((keys.head match {
        case b1.blockHash => b1
        case b2.blockHash => b2
        case b3.blockHash => b3
      }).some)
    }

    val bds = mock[BlockDagStorage[F]]

    val m1 = toMessage(b1)
    val m2 = toMessage(b2)
    val m3 = toMessage(b3)

    bds.getRepresentation returnsF DagRepresentation(
      Set(m1.id, m2.id, m3.id),
      Map(m1.id    -> Set(m2.id, m3.id)),
      SortedMap(0L -> Set(m1.id), 1L -> Set(m2.id, m3.id)),
      new DagMessageState(Set(m2, m3), Map(m1.id -> m1, m2.id -> m2, m3.id -> m3)),
      Map.empty
    )

    (log, sp, rm, bs, bds)
  }
}
