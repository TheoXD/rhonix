package io.rhonix.node.revvaultexport

import cats.effect.Concurrent
import io.rhonix.casper.genesis.contracts.{Registry, StandardDeploys}
import io.rhonix.casper.helper.TestNode.Effect
import io.rhonix.casper.helper.TestRhoRuntime.rhoRuntimeEff
import io.rhonix.casper.syntax._
import io.rhonix.casper.util.{ConstructDeploy, GenesisBuilder}
import io.rhonix.crypto.hash.Blake2b512Random
import io.rhonix.metrics.{Metrics, NoopSpan, Span}
import io.rhonix.models.rholang.RhoType.RhoName
import io.rhonix.models.syntax._
import io.rhonix.shared.Log
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global
import org.scalatest.flatspec.AnyFlatSpec

import scala.collection.compat.immutable.LazyList
import scala.util.Random

class RhoTrieTraverserTest extends AnyFlatSpec {
  private val SHARD_ID = "root-shard"
  private val registry = Registry(GenesisBuilder.defaultSystemContractPubKey)

  "traverse the TreeHashMap" should "work" in {
    val total     = 100
    val trieDepth = 2
    val insertKeyValues = (0 to total).map(
      i => (Random.alphanumeric.take(10).foldLeft("")(_ + _), Random.nextInt(1000000), i)
    )
    val insertRho = insertKeyValues.foldLeft("") {
      case (acc, (key, value, index)) =>
        if (index != total)
          acc + s"""new a in {@TreeHashMap!("set", treeMap, "${key}", ${value}, *a)}|\n"""
        else acc + s"""new a in {@TreeHashMap!("set", treeMap, "${key}", ${value}, *a)}\n"""
    }
    val trieInitializedRho =
      s"""
        |new
        |  rl(`rho:registry:lookup`),
        |  TreeHashMapCh,
        |  newTreeMapStore,
        |  vaultMapStore
        |  in {
        |  rl!(`rho:lang:treeHashMap`, *TreeHashMapCh) |
        |  for (@(_, TreeHashMap) <- TreeHashMapCh){
        |    @TreeHashMap!("init", ${trieDepth}, *vaultMapStore) |
        |    for (@treeMap <-  vaultMapStore){
        |      ${insertRho}
        |      |@"t"!(treeMap)
        |    }
        |  }
        |}
        |""".stripMargin

    val getTrieMapHandleRho = """new s in {
                               |  for (@result<- @"t"){
                               |    s!(result)
                               |  }
                               |}""".stripMargin

    implicit val concurrent                  = Concurrent[Task]
    implicit val metricsEff: Metrics[Effect] = new Metrics.MetricsNOP[Task]
    implicit val noopSpan: Span[Effect]      = NoopSpan[Task]()
    implicit val logger: Log[Effect]         = Log.log[Task]
    val t = rhoRuntimeEff[Effect](false).use {
      case (runtime, _, _) =>
        for {
          hash1 <- runtime.emptyStateHash
          _     <- runtime.reset(hash1.toBlake2b256Hash)
          rand  = Blake2b512Random.defaultRandom
          storeToken = {
            val r      = rand.copy()
            val target = LazyList.continually(r.next()).drop(9).head
            RhoName(target)
          }
          rd <- runtime.processDeploy(
                 StandardDeploys.registryGenerator(registry, SHARD_ID),
                 rand
               )
          check <- runtime.createCheckpoint
          _     <- runtime.reset(check.root)
          initialTrieRes <- runtime.processDeploy(
                             ConstructDeploy
                               .sourceDeploy(
                                 trieInitializedRho,
                                 1L,
                                 phloLimit = 50000000
                               ),
                             Blake2b512Random.defaultRandom
                           )
          (initialTrie, _) = initialTrieRes
          _                = assert(!initialTrie.isFailed)
          check2           <- runtime.createCheckpoint
          trieMapHandleR <- runtime.playExploratoryDeploy(
                             getTrieMapHandleRho,
                             check2.root.toByteString
                           )
          _             <- runtime.reset(check2.root)
          trieMapHandle = trieMapHandleR.head
          maps          <- RhoTrieTraverser.traverseTrie(trieDepth, trieMapHandle, storeToken, runtime)
          goodMap = RhoTrieTraverser.vecParMapToMap(
            maps,
            p => p.exprs.head.getGByteArray,
            p => p.exprs.head.getGInt
          )
          _ = insertKeyValues.map(k => {
            val key =
              RhoTrieTraverser.keccakKey(k._1).exprs.head.getGByteArray.substring(trieDepth, 32)
            assert(goodMap(key) == k._2.toLong)
          })
        } yield ()
    }
    t.runSyncUnsafe()
  }

}
