package io.rhonix.casper.batch1

import io.rhonix.casper.helper.TestNode
import io.rhonix.casper.helper.TestNode.Effect
import io.rhonix.casper.protocol.BlockMessage
import io.rhonix.casper.rholang.{BlockRandomSeed, RuntimeManager, Tools}
import io.rhonix.casper.util.{ConstructDeploy, ProtoUtil, RSpaceUtil}
import io.rhonix.crypto.signatures.Secp256k1
import io.rhonix.p2p.EffectsTestInstances.LogicalTime
import io.rhonix.rholang.interpreter.SystemProcesses.BlockData
import io.rhonix.models.syntax._
import io.rhonix.shared.Base16
import io.rhonix.shared.scalatestcontrib._
import monix.execution.Scheduler.Implicits.global
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.Inspectors
import org.scalatest.matchers.should.Matchers

class MultiParentCasperRholangSpec extends AnyFlatSpec with Matchers with Inspectors {

  import RSpaceUtil._
  import io.rhonix.casper.util.GenesisBuilder._

  implicit val timeEff: LogicalTime[Effect] = new LogicalTime[Effect]

  val genesis = buildGenesis()

  "MultiParentCasper" should "create blocks based on deploys" in effectTest {
    TestNode.standaloneEff(genesis).use { implicit node =>
      implicit val rm: RuntimeManager[Effect] = node.runtimeManager

      for {
        deploy         <- ConstructDeploy.basicDeployData[Effect](0)
        block          <- node.createBlockUnsafe(deploy)
        deploys        = block.state.deploys.map(_.deploy)
        justifications = block.justifications

        _      = justifications.size should be(1)
        _      = justifications.head should be(genesis.genesisBlock.blockHash)
        _      = deploys.size should be(1)
        _      = deploys.head should be(deploy)
        data   <- getDataAtPublicChannel[Effect](block, 0)
        result = data shouldBe Seq("0")
      } yield result
    }
  }

  it should "be able to use the registry" in effectTest {
    TestNode.standaloneEff(genesis).use { node =>
      implicit val rm: RuntimeManager[Effect] = node.runtimeManager

      val registerSource =
        """
          |new uriCh, rr(`rho:registry:insertArbitrary`), hello in {
          |  contract hello(@name, return) = {
          |    return!("Hello, ${name}!" %% {"name" : name})
          |  } |
          |  rr!(bundle+{*hello}, *uriCh)
          |}
        """.stripMargin

      def callSource(registryId: String) =
        s"""
           |new out, rl(`rho:registry:lookup`), helloCh in {
           |  rl!($registryId, *helloCh) |
           |  for(hello <- helloCh){
           |    hello!("World", *out)
           |  }
           |}
         """.stripMargin

      def calculateDeployUnforgeableName(block: BlockMessage): String = {
        val rand              = BlockRandomSeed.randomGenerator(block)
        val deployIndex: Byte = 0
        rand
          .splitByte(deployIndex)
          .splitByte(BlockRandomSeed.UserDeploySplitIndex)
          .next()
          .toHexString
      }

      for {
        registerDeploy <- ConstructDeploy
                           .sourceDeployNowF(registerSource, shardId = genesis.genesisBlock.shardId)
        block0 <- node.addBlock(registerDeploy)
        registryId <- getDataAtPrivateChannel[Effect](
                       block0,
                       calculateDeployUnforgeableName(block0)
                     )
        callDeploy <- ConstructDeploy.sourceDeployNowF(
                       callSource(registryId.head),
                       shardId = genesis.genesisBlock.shardId
                     )
        block1 <- node.addBlock(callDeploy)
        data <- getDataAtPrivateChannel[Effect](
                 block1,
                 calculateDeployUnforgeableName(block1)
               )
        _ = data shouldBe Seq("\"Hello, World!\"")
      } yield ()
    }
  }
}
