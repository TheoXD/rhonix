package io.rhonix.casper.rholang

import cats.effect.Resource
import cats.syntax.all._
import com.google.protobuf.ByteString
import io.rhonix.casper.genesis.Genesis
import io.rhonix.casper.helper.TestNode
import io.rhonix.casper.rholang.Resources._
import io.rhonix.casper.util.GenesisBuilder.buildGenesis
import io.rhonix.casper.util.{ConstructDeploy, GenesisBuilder}
import io.rhonix.crypto.PrivateKey
import io.rhonix.crypto.signatures.Secp256k1
import io.rhonix.casper.syntax._
import io.rhonix.models.Expr.ExprInstance.GBool
import io.rhonix.models.rholang.implicits._
import io.rhonix.models.{GDeployerId, Par}
import io.rhonix.p2p.EffectsTestInstances.LogicalTime
import io.rhonix.shared.scalatestcontrib.effectTest
import io.rhonix.shared.{Base16, Log}
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class DeployerIdTest extends AnyFlatSpec with Matchers {
  implicit val time              = new LogicalTime[Task]
  implicit val log: Log[Task]    = new Log.NOPLog[Task]()
  private val dummyMergeableName = BlockRandomSeed.nonNegativeMergeableTagName("dummy")

  val runtimeManager: Resource[Task, RuntimeManager[Task]] =
    mkRuntimeManager[Task]("deployer-id-runtime-manager-test", dummyMergeableName)

  "Deployer id" should "be equal to the deployer's public key" in effectTest {
    val sk = PrivateKey(
      Base16.unsafeDecode("b18e1d0045995ec3d010c387ccfeb984d783af8fbb0f40fa7db126d889f6dadd")
    )
    val pk = ByteString.copyFrom(Secp256k1.toPublic(sk).bytes)
    runtimeManager.use { mgr =>
      for {
        deploy <- ConstructDeploy.sourceDeployNowF(
                   s"""new return, auth(`rho:rhonix:deployerId`) in { return!(*auth) }""",
                   sec = sk
                 )
        emptyStateHash = RuntimeManager.emptyStateHashFixed
        result         <- mgr.spawnRuntime >>= { _.captureResults(emptyStateHash, deploy) }
        _              = result.size should be(1)
        _              = result.head should be(GDeployerId(pk): Par)
      } yield ()
    }
  }

  val genesisContext = buildGenesis(GenesisBuilder.buildGenesisParametersSize(4))

  it should "make drain vault attacks impossible" in effectTest {
    val deployer = ConstructDeploy.defaultSec
    val attacker = ConstructDeploy.defaultSec2

    checkAccessGranted(deployer, deployer, isAccessGranted = true) >>
      checkAccessGranted(deployer, attacker, isAccessGranted = false)
  }

  def checkAccessGranted(
      deployer: PrivateKey,
      contractUser: PrivateKey,
      isAccessGranted: Boolean
  ): Task[Unit] = {
    val checkDeployerDefinition =
      s"""
         |contract @"checkAuth"(input, ret) = {
         |  new auth(`rho:rhonix:deployerId`) in {
         |    ret!(*input == *auth)
         |  }
         |}""".stripMargin
    val checkDeployerCall =
      s"""
         |new return, auth(`rho:rhonix:deployerId`), ret in {
         |  @"checkAuth"!(*auth, *ret) |
         |  for(isAuthenticated <- ret) {
         |    return!(*isAuthenticated)
         |  }
         |} """.stripMargin

    TestNode.standaloneEff(genesisContext).use { node =>
      for {
        contract <- ConstructDeploy.sourceDeployNowF(
                     checkDeployerDefinition,
                     sec = deployer,
                     shardId = genesisContext.genesisBlock.shardId
                   )
        block           <- node.addBlock(contract)
        stateHash       = block.postStateHash
        checkAuthDeploy <- ConstructDeploy.sourceDeployNowF(checkDeployerCall, sec = contractUser)
        result <- node.runtimeManager.spawnRuntime >>= {
                   _.captureResults(stateHash, checkAuthDeploy)
                 }
        _ = assert(result.size == 1)
        _ = assert(result.head == (GBool(isAccessGranted): Par))
      } yield ()
    }
  }

}
