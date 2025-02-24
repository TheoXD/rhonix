package io.rhonix.rholang.interpreter

import cats.effect.Concurrent
import cats.effect.concurrent.Ref
import cats.syntax.all._
import com.google.protobuf.ByteString
import com.typesafe.scalalogging.Logger
import io.rhonix.casper.protocol.BlockMessage
import io.rhonix.crypto.PublicKey
import io.rhonix.crypto.hash.{Blake2b256, Keccak256, Sha256}
import io.rhonix.crypto.signatures.{Ed25519, Secp256k1}
import io.rhonix.metrics.Span
import io.rhonix.models._
import io.rhonix.models.rholang.RhoType
import io.rhonix.models.rholang.implicits._
import io.rhonix.rholang.interpreter.RhoRuntime.RhoTuplespace
import io.rhonix.rholang.interpreter.RholangAndScalaDispatcher.RhoDispatch
import io.rhonix.rholang.interpreter.registry.Registry
import io.rhonix.rholang.interpreter.util.RevAddress
import io.rhonix.rspace.{ContResult, Result}
import io.rhonix.shared.Base16

import scala.util.Try

//TODO: Make each of the system processes into a case class,
//      so that implementation is not repetitive.
//TODO: Make polymorphic over match type.
trait SystemProcesses[F[_]] {
  import SystemProcesses.Contract

  def stdOut: Contract[F]
  def stdOutAck: Contract[F]
  def stdErr: Contract[F]
  def stdErrAck: Contract[F]
  def secp256k1Verify: Contract[F]
  def ed25519Verify: Contract[F]
  def sha256Hash: Contract[F]
  def keccak256Hash: Contract[F]
  def blake2b256Hash: Contract[F]
  def getBlockData(blockData: Ref[F, SystemProcesses.BlockData]): Contract[F]
  def revAddress: Contract[F]
  def deployerIdOps: Contract[F]
  def registryOps: Contract[F]
  def sysAuthTokenOps: Contract[F]
}

object SystemProcesses {
  type RhoSysFunction[F[_]] = Seq[ListParWithRandom] => F[Unit]
  type RhoDispatchMap[F[_]] = Map[Long, RhoSysFunction[F]]
  type Name                 = Par
  type Arity                = Int
  type Remainder            = Option[Var]
  type BodyRef              = Long

  final case class BlockData private (
      blockNumber: Long,
      sender: PublicKey,
      seqNum: Long
  )

  def byteName(b: Byte): Par = GPrivate(ByteString.copyFrom(Array[Byte](b)))

  object FixedChannels {
    val STDOUT: Par             = byteName(0)
    val STDOUT_ACK: Par         = byteName(1)
    val STDERR: Par             = byteName(2)
    val STDERR_ACK: Par         = byteName(3)
    val ED25519_VERIFY: Par     = byteName(4)
    val SHA256_HASH: Par        = byteName(5)
    val KECCAK256_HASH: Par     = byteName(6)
    val BLAKE2B256_HASH: Par    = byteName(7)
    val SECP256K1_VERIFY: Par   = byteName(8)
    val GET_BLOCK_DATA: Par     = byteName(10)
    val GET_INVALID_BLOCKS: Par = byteName(11)
    val REV_ADDRESS: Par        = byteName(12)
    val DEPLOYER_ID_OPS: Par    = byteName(13)
    val REG_LOOKUP: Par         = byteName(14)
    val REG_INSERT_RANDOM: Par  = byteName(15)
    val REG_INSERT_SIGNED: Par  = byteName(16)
    val REG_OPS: Par            = byteName(17)
    val SYS_AUTHTOKEN_OPS: Par  = byteName(18)
  }
  object BodyRefs {
    val STDOUT: Long             = 0L
    val STDOUT_ACK: Long         = 1L
    val STDERR: Long             = 2L
    val STDERR_ACK: Long         = 3L
    val ED25519_VERIFY: Long     = 4L
    val SHA256_HASH: Long        = 5L
    val KECCAK256_HASH: Long     = 6L
    val BLAKE2B256_HASH: Long    = 7L
    val SECP256K1_VERIFY: Long   = 9L
    val GET_BLOCK_DATA: Long     = 11L
    val GET_INVALID_BLOCKS: Long = 12L
    val REV_ADDRESS: Long        = 13L
    val DEPLOYER_ID_OPS: Long    = 14L
    val REG_OPS: Long            = 15L
    val SYS_AUTHTOKEN_OPS: Long  = 16L
  }
  final case class ProcessContext[F[_]: Concurrent: Span](
      space: RhoTuplespace[F],
      dispatcher: RhoDispatch[F],
      blockData: Ref[F, BlockData]
  ) {
    val systemProcesses = SystemProcesses[F](dispatcher, space)
  }
  final case class Definition[F[_]](
      urn: String,
      fixedChannel: Name,
      arity: Arity,
      bodyRef: BodyRef,
      handler: ProcessContext[F] => Seq[ListParWithRandom] => F[Unit],
      remainder: Remainder = None
  ) {
    def toDispatchTable(
        context: ProcessContext[F]
    ): (BodyRef, Seq[ListParWithRandom] => F[Unit]) =
      bodyRef -> handler(context)

    def toUrnMap: (String, Par) = {
      val bundle: Par = Bundle(fixedChannel, writeFlag = true)
      urn -> bundle
    }

    def toProcDefs: (Name, Arity, Remainder, BodyRef) =
      (fixedChannel, arity, remainder, bodyRef)
  }
  object BlockData {
    def empty: BlockData = BlockData(0, PublicKey(Base16.unsafeDecode("00")), 0)
    def fromBlock(template: BlockMessage) =
      BlockData(
        template.blockNumber,
        PublicKey(template.sender),
        template.seqNum
      )
  }
  type Contract[F[_]] = Seq[ListParWithRandom] => F[Unit]

  def apply[F[_]](
      dispatcher: Dispatch[F, ListParWithRandom, TaggedContinuation],
      space: RhoTuplespace[F]
  )(implicit F: Concurrent[F], spanF: Span[F]): SystemProcesses[F] =
    new SystemProcesses[F] {

      type ContWithMetaData = ContResult[Par, BindPattern, TaggedContinuation]

      type Channels = Seq[Result[Par, ListParWithRandom]]

      private val prettyPrinter = PrettyPrinter()

      private val isContractCall = new ContractCall[F](space, dispatcher)

      private val stdOutLogger = Logger("io.rhonix.rholang.stdout")
      private val stdErrLogger = Logger("io.rhonix.rholang.stderr")

      private def illegalArgumentException(msg: String): F[Unit] =
        F.raiseError(new IllegalArgumentException(msg))

      def verifySignatureContract(
          name: String,
          algorithm: (Array[Byte], Array[Byte], Array[Byte]) => Boolean
      ): Contract[F] = {
        case isContractCall(
            produce,
            Seq(
              RhoType.RhoByteArray(data),
              RhoType.RhoByteArray(signature),
              RhoType.RhoByteArray(pub),
              ack
            )
            ) =>
          for {
            verified <- F.fromTry(Try(algorithm(data, signature, pub)))
            _        <- produce(Seq(RhoType.RhoBoolean(verified)), ack)
          } yield ()
        case _ =>
          illegalArgumentException(
            s"$name expects data, signature, public key (all as byte arrays), and an acknowledgement channel"
          )
      }

      def hashContract(name: String, algorithm: Array[Byte] => Array[Byte]): Contract[F] = {
        case isContractCall(produce, Seq(RhoType.RhoByteArray(input), ack)) =>
          for {
            hash <- F.fromTry(Try(algorithm(input)))
            _    <- produce(Seq(RhoType.RhoByteArray(hash)), ack)
          } yield ()
        case _ =>
          illegalArgumentException(
            s"$name expects a byte array and return channel"
          )
      }

      private def printStdOut(s: String): F[Unit] =
        for {
          _ <- F.delay(Console.println(s))
          _ <- F.delay(stdOutLogger.debug(s))
        } yield ()

      private def printStdErr(s: String): F[Unit] =
        for {
          _ <- F.delay(Console.err.println(s))
          _ <- F.delay(stdErrLogger.debug(s))
        } yield ()

      def stdOut: Contract[F] = {
        case isContractCall(_, Seq(arg)) =>
          printStdOut(prettyPrinter.buildString(arg))
      }

      def stdOutAck: Contract[F] = {
        case isContractCall(produce, Seq(arg, ack)) =>
          for {
            _ <- printStdOut(prettyPrinter.buildString(arg))
            _ <- produce(Seq(Par.defaultInstance), ack)
          } yield ()
      }

      def stdErr: Contract[F] = {
        case isContractCall(_, Seq(arg)) =>
          printStdErr(prettyPrinter.buildString(arg))
      }

      def stdErrAck: Contract[F] = {
        case isContractCall(produce, Seq(arg, ack)) =>
          for {
            _ <- printStdErr(prettyPrinter.buildString(arg))
            _ <- produce(Seq(Par.defaultInstance), ack)
          } yield ()
      }

      def revAddress: Contract[F] = {
        case isContractCall(
            produce,
            Seq(RhoType.RhoString("validate"), RhoType.RhoString(address), ack)
            ) =>
          val errorMessage =
            RevAddress
              .parse(address)
              .swap
              .toOption
              .map(RhoType.RhoString(_))
              .getOrElse(Par())

          produce(Seq(errorMessage), ack)

        // TODO: Invalid type for address should throw error!
        case isContractCall(produce, Seq(RhoType.RhoString("validate"), _, ack)) =>
          produce(Seq(Par()), ack)

        case isContractCall(
            produce,
            Seq(RhoType.RhoString("fromPublicKey"), RhoType.RhoByteArray(publicKey), ack)
            ) =>
          val response =
            RevAddress
              .fromPublicKey(PublicKey(publicKey))
              .map(ra => RhoType.RhoString(ra.toBase58))
              .getOrElse(Par())

          produce(Seq(response), ack)

        case isContractCall(produce, Seq(RhoType.RhoString("fromPublicKey"), _, ack)) =>
          produce(Seq(Par()), ack)

        case isContractCall(
            produce,
            Seq(RhoType.RhoString("fromDeployerId"), RhoType.RhoDeployerId(id), ack)
            ) =>
          val response =
            RevAddress
              .fromDeployerId(id)
              .map(ra => RhoType.RhoString(ra.toBase58))
              .getOrElse(Par())

          produce(Seq(response), ack)

        case isContractCall(produce, Seq(RhoType.RhoString("fromDeployerId"), _, ack)) =>
          produce(Seq(Par()), ack)

        case isContractCall(
            produce,
            Seq(RhoType.RhoString("fromUnforgeable"), argument, ack)
            ) =>
          val response = argument match {
            case RhoType.RhoName(gprivate) =>
              RhoType.RhoString(RevAddress.fromUnforgeable(gprivate).toBase58)
            case _ => Par()
          }

          produce(Seq(response), ack)

        case isContractCall(produce, Seq(RhoType.RhoString("fromUnforgeable"), _, ack)) =>
          produce(Seq(Par()), ack)
      }

      def deployerIdOps: Contract[F] = {
        case isContractCall(
            produce,
            Seq(RhoType.RhoString("pubKeyBytes"), RhoType.RhoDeployerId(publicKey), ack)
            ) =>
          produce(Seq(RhoType.RhoByteArray(publicKey)), ack)

        case isContractCall(produce, Seq(RhoType.RhoString("pubKeyBytes"), _, ack)) =>
          produce(Seq(Par()), ack)
      }

      def registryOps: Contract[F] = {
        case isContractCall(
            produce,
            Seq(RhoType.RhoString("buildUri"), argument, ack)
            ) =>
          val response = argument match {
            case RhoType.RhoByteArray(ba) =>
              val hashKeyBytes = Blake2b256.hash(ba)
              RhoType.RhoUri(Registry.buildURI(hashKeyBytes))
            case _ => Par()
          }
          produce(Seq(response), ack)
      }

      def sysAuthTokenOps: Contract[F] = {
        case isContractCall(
            produce,
            Seq(RhoType.RhoString("check"), argument, ack)
            ) =>
          val response = argument match {
            case RhoType.RhoSysAuthToken(_) => RhoType.RhoBoolean(true)
            case _                          => RhoType.RhoBoolean(false)
          }
          produce(Seq(response), ack)
      }

      def secp256k1Verify: Contract[F] =
        verifySignatureContract("secp256k1Verify", Secp256k1.verify)

      def ed25519Verify: Contract[F] =
        verifySignatureContract("ed25519Verify", Ed25519.verify)

      def sha256Hash: Contract[F] =
        hashContract("sha256Hash", Sha256.hash)

      def keccak256Hash: Contract[F] =
        hashContract("keccak256Hash", Keccak256.hash)

      def blake2b256Hash: Contract[F] =
        hashContract("blake2b256Hash", Blake2b256.hash)

      def getBlockData(
          blockData: Ref[F, BlockData]
      ): Contract[F] = {
        case isContractCall(produce, Seq(ack)) =>
          for {
            data <- blockData.get
            _ <- produce(
                  Seq(
                    RhoType.RhoNumber(data.blockNumber),
                    RhoType.RhoByteArray(data.sender.bytes)
                  ),
                  ack
                )
          } yield ()
        case _ =>
          illegalArgumentException("blockData expects only a return channel")
      }
    }
}
