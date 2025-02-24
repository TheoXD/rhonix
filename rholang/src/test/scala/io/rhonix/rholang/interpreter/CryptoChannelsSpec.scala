package io.rhonix.rholang.interpreter

import com.google.protobuf.ByteString
import io.rhonix.crypto.hash.{Blake2b256, Blake2b512Random, Keccak256, Sha256}
import io.rhonix.crypto.signatures.{Ed25519, Secp256k1}
import io.rhonix.metrics
import io.rhonix.metrics.{Metrics, NoopSpan, Span}
import io.rhonix.models.Expr.ExprInstance.{GBool, GByteArray, GString}
import io.rhonix.models.Var.VarInstance.Wildcard
import io.rhonix.models.Var.WildcardMsg
import io.rhonix.models._
import io.rhonix.models.rholang.RhoType
import io.rhonix.models.rholang.implicits._
import io.rhonix.models.testImplicits._
import io.rhonix.rholang.Resources
import io.rhonix.rholang.interpreter.SystemProcesses.FixedChannels
import io.rhonix.rholang.interpreter.accounting.Cost
import io.rhonix.rspace.syntax.rspaceSyntaxKeyValueStoreManager
import io.rhonix.shared.PathOps._
import io.rhonix.shared.{Base16, Log, Serialize}
import io.rhonix.store.InMemoryStoreManager
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global
import org.scalactic.TripleEqualsSupport
import org.scalatest.flatspec.FixtureAnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.{Assertion, Outcome}
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

import java.nio.file.Files
import scala.collection.immutable.BitSet
import scala.concurrent.Await
import scala.concurrent.duration._

class CryptoChannelsSpec
    extends FixtureAnyFlatSpec
    with ScalaCheckPropertyChecks
    with Matchers
    with TripleEqualsSupport {

  behavior of "Crypto channels"

  implicit val rand: Blake2b512Random                      = Blake2b512Random(Array.empty[Byte])
  implicit val serializePar: Serialize[Par]                = storage.serializePar
  implicit val serializePars: Serialize[ListParWithRandom] = storage.serializePars

  val serialize: Par => Array[Byte]                    = Serialize[Par].encode(_).toArray
  val byteArrayToByteString: Array[Byte] => ByteString = ba => ByteString.copyFrom(ba)
  val byteStringToExpr: ByteString => Expr             = bs => Expr(GByteArray(bs))
  val byteArrayToExpr: Array[Byte] => Expr             = byteArrayToByteString andThen byteStringToExpr
  val parToByteString: Par => ByteString               = serialize andThen (ba => ByteString.copyFrom(ba))
  val parToExpr: Par => Expr                           = parToByteString andThen byteStringToExpr

  // this should consume from the `ack` channel effectively preparing tuplespace for next test
  def clearStore(
      ackChannel: Par,
      timeout: Duration = 3.seconds
  )(implicit env: Env[Par], runtime: RhoRuntime[Task]): Unit = {
    val consume: Par = Receive(
      Seq(ReceiveBind(Seq(EVar(Var(Wildcard(WildcardMsg())))), ackChannel)),
      Par()
    )
    Await.ready(runtime.inj(consume, env).runToFuture, 3.seconds)
  }

  def assertStoreContains(
      runtime: RhoRuntime[Task]
  )(ackChannel: GString)(data: ListParWithRandom): Task[Assertion] = {
    val channel: Par = ackChannel
    for {
      spaceMap <- runtime.getHotChanges
      datum    = spaceMap(List(channel)).data.head
    } yield {
      assert(datum.a.pars == data.pars)
      assert(datum.a.randomState == data.randomState)
      assert(!datum.persist)
    }
  }

  def hashingChannel(
      channelName: String,
      hashFn: Array[Byte] => Array[Byte],
      fixture: FixtureParam
  ): Any = {
    implicit val runtime = fixture

    val hashChannel: Par = channelName match {
      case "sha256Hash"     => FixedChannels.SHA256_HASH
      case "keccak256Hash"  => FixedChannels.KECCAK256_HASH
      case "blake2b256Hash" => FixedChannels.BLAKE2B256_HASH
    }

    val ackChannel                  = GString("x")
    implicit val emptyEnv: Env[Par] = Env[Par]()

    val storeContainsTest: ListParWithRandom => Task[Assertion] =
      assertStoreContains(runtime)(ackChannel)(_)

    forAll { par: Par =>
      val toByteArray: Array[Byte] = serialize(par)
      val byteArrayToSend: Par     = GByteArray(ByteString.copyFrom(toByteArray))
      val data: List[Par]          = List(byteArrayToSend, ackChannel)
      val send                     = Send(hashChannel, data, persistent = false, BitSet())
      val expected                 = RhoType.RhoByteArray(hashFn(toByteArray))

      // Send byte array on hash channel. This should:
      // 1. meet with the system process in the tuplespace
      // 2. hash input array
      // 3. send result on supplied ack channel
      (runtime.inj(send) >>
        storeContainsTest(ListParWithRandom(Seq(expected), rand))).runSyncUnsafe(3.seconds)
      clearStore(ackChannel)
    }
  }

  "sha256Hash channel" should "hash input data and send result on ack channel" in { fixture =>
    hashingChannel("sha256Hash", Sha256.hash, fixture)
  }

  "blake2b256Hash channel" should "hash input data and send result on ack channel" in { fixture =>
    hashingChannel("blake2b256Hash", Blake2b256.hash, fixture)
  }

  "keccak256Hash channel" should "hash input data and send result on ack channel" in { fixture =>
    hashingChannel("keccak256Hash", Keccak256.hash, fixture)
  }

  type Signature  = Array[Byte]
  type PubKey     = Array[Byte]
  type PrivateKey = Array[Byte]
  type Nonce      = Array[Byte]
  type Data       = Array[Byte]

  "secp256k1Verify channel" should "verify integrity of the data and send result on ack channel" in {
    fixture =>
      implicit val runtime = fixture

      val secp256k1VerifyhashChannel = FixedChannels.SECP256K1_VERIFY

      val pubKey = Base16.unsafeDecode(
        "04C591A8FF19AC9C4E4E5793673B83123437E975285E7B442F4EE2654DFFCA5E2D2103ED494718C697AC9AEBCFD19612E224DB46661011863ED2FC54E71861E2A6"
      )
      val secKey =
        Base16.unsafeDecode("67E56582298859DDAE725F972992A07C6C4FB9F62A8FFF58CE3CA926A1063530")

      val ackChannel                  = GString("x")
      implicit val emptyEnv: Env[Par] = Env[Par]()
      val storeContainsTest: ListParWithRandom => Task[Assertion] =
        assertStoreContains(runtime)(ackChannel)

      forAll { par: Par =>
        val parByteArray: Array[Byte] = Keccak256.hash(serialize(par))

        val signature = Secp256k1.sign(parByteArray, secKey)

        val serializedPar = byteArrayToExpr(parByteArray)
        val signaturePar  = byteArrayToExpr(signature)
        val pubKeyPar     = byteArrayToExpr(pubKey)

        val refVerify = Secp256k1.verify(parByteArray, signature, pubKey)
        assert(refVerify == true)

        val send = Send(
          secp256k1VerifyhashChannel,
          List(serializedPar, signaturePar, pubKeyPar, ackChannel),
          persistent = false,
          BitSet()
        )
        (runtime.inj(send) >>
          storeContainsTest(
            ListParWithRandom(Seq(Expr(GBool(true))), rand)
          )).runSyncUnsafe(3.seconds)
        clearStore(ackChannel)
      }
  }

  "ed25519Verify channel" should "verify integrity of the data and send result on ack channel" in {
    fixture =>
      implicit val runtime = fixture

      implicit val rand: Blake2b512Random = Blake2b512Random(Array.empty[Byte])

      val ed25519VerifyChannel = FixedChannels.ED25519_VERIFY;
      val (secKey, pubKey)     = Ed25519.newKeyPair

      val ackChannel                  = GString("x")
      implicit val emptyEnv: Env[Par] = Env[Par]()
      val storeContainsTest: ListParWithRandom => Task[Assertion] =
        assertStoreContains(runtime)(ackChannel)

      forAll { par: Par =>
        val parByteArray: Array[Byte] = serialize(par)

        val signature = Ed25519.sign(parByteArray, secKey)

        val serializedPar = byteArrayToExpr(parByteArray)
        val signaturePar  = byteArrayToExpr(signature)
        val pubKeyPar     = byteArrayToExpr(pubKey.bytes)

        val refVerify = Ed25519.verify(parByteArray, signature, pubKey)
        assert(refVerify == true)

        val send = Send(
          ed25519VerifyChannel,
          List(serializedPar, signaturePar, pubKeyPar, ackChannel),
          persistent = false,
          BitSet()
        )
        (runtime.inj(send) >> storeContainsTest(
          ListParWithRandom(List(Expr(GBool(true))), rand)
        )).runSyncUnsafe(3.seconds)
        clearStore(ackChannel)
      }
  }

  protected override def withFixture(test: OneArgTest): Outcome = {
    val randomInt                           = scala.util.Random.nextInt
    val dbDir                               = Files.createTempDirectory(s"rhonix-storage-test-$randomInt-")
    implicit val logF: Log[Task]            = new Log.NOPLog[Task]
    implicit val noopMetrics: Metrics[Task] = new metrics.Metrics.MetricsNOP[Task]
    implicit val noopSpan: Span[Task]       = NoopSpan[Task]()
    implicit val kvm                        = InMemoryStoreManager[Task]

    val runtime = (for {
      store                       <- kvm.rSpaceStores
      spaces                      <- Resources.createRuntimes[Task](store)
      (runtime, replayRuntime, _) = spaces
      _                           <- runtime.cost.set(Cost.UNSAFE_MAX)
    } yield runtime).runSyncUnsafe()

    try {
      test(runtime)
    } finally {
      kvm.shutdown.runSyncUnsafe()
      dbDir.recursivelyDelete()
    }
  }

  /** TODO(mateusz.gorski): once we refactor Rholang[AndScala]Dispatcher
    *  to push effect choice up until declaration site refactor to `Reduce[Coeval]`
    */
  override type FixtureParam = RhoRuntime[Task]

}
