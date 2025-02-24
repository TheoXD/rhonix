package io.rhonix.casper.protocol

import cats.Monad
import cats.effect._
import cats.syntax.all._
import cats.tagless.autoFunctorK
import com.google.protobuf.ByteString
import io.rhonix.casper._
import io.rhonix.casper.protocol.CommUtil.StandaloneNodeSendToBootstrapError
import io.rhonix.comm.protocol.routing.{Packet, Protocol}
import io.rhonix.comm.rp.Connect.{ConnectionsCell, RPConfAsk}
import io.rhonix.comm.rp.ProtocolHelper.packet
import io.rhonix.comm.syntax._
import io.rhonix.comm.transport.{Blob, TransportLayer}
import io.rhonix.comm.{CommError, PeerNode}
import io.rhonix.models.BlockHash.BlockHash
import io.rhonix.rspace.hashing.Blake2b256Hash
import io.rhonix.shared._

import scala.concurrent.duration._

// TODO: remove CommUtil completely and move to extensions (syntax) on TransportLayer
@autoFunctorK
trait CommUtil[F[_]] {
  // Broadcast packet (in one piece)
  def sendToPeers(message: Packet, scopeSize: Option[Int] = None): F[Unit]

  // Broadcast packet in chunks (stream)
  def streamToPeers(packet: Packet, scopeSize: Option[Int] = None): F[Unit]

  // Send packet with retry
  def sendWithRetry(
      message: Packet,
      peer: PeerNode,
      retryAfter: FiniteDuration,
      messageTypeName: String // Only for log message / should be removed with CommUtil refactor
  ): F[Unit]

  // Reqest for BlockMessage
  def requestForBlock(peer: PeerNode, hash: BlockHash): F[Unit]
}

object CommUtil {

  implicit private val logSource: LogSource = LogSource(this.getClass)

  // Standalone (bootstrap) node should try send messages to bootstrap node
  final case object StandaloneNodeSendToBootstrapError extends Exception

  def apply[F[_]](implicit ev: CommUtil[F]): CommUtil[F] = ev

  def of[F[_]: Concurrent: Timer: TransportLayer: RPConfAsk: ConnectionsCell: Log]: CommUtil[F] =
    new CommUtil[F] {

      def sendToPeers(message: Packet, scopeSize: Option[Int]): F[Unit] =
        for {
          max <- if (scopeSize.isEmpty) RPConfAsk[F].reader(_.maxNumOfConnections)
                else scopeSize.get.pure[F]
          peers <- ConnectionsCell.random(max)
          conf  <- RPConfAsk[F].ask
          msg   = packet(conf.local, conf.networkId, message)
          _     <- TransportLayer[F].broadcast(peers, msg)
        } yield ()

      def streamToPeers(packet: Packet, scopeSize: Option[Int]): F[Unit] =
        for {
          max <- if (scopeSize.isEmpty) RPConfAsk[F].reader(_.maxNumOfConnections)
                else scopeSize.get.pure[F]
          peers <- ConnectionsCell.random(max)
          local <- RPConfAsk[F].reader(_.local)
          msg   = Blob(local, packet)
          _     <- TransportLayer[F].stream(peers, msg)
        } yield ()

      def sendWithRetry(
          message: Packet,
          peer: PeerNode,
          retryAfter: FiniteDuration,
          msgTypeName: String
      ): F[Unit] = {
        def keepOnRequestingTillRunning(peer: PeerNode, msg: Protocol): F[Unit] =
          TransportLayer[F].send(peer, msg) >>= {
            case Right(_) =>
              Log[F].info(s"Successfully sent ${msgTypeName} to $peer")
            case Left(error) =>
              Log[F].warn(
                s"Failed to send ${msgTypeName} to $peer because of ${CommError
                  .errorMessage(error)}. Retrying in $retryAfter..."
              ) >> Timer[F].sleep(retryAfter) >> keepOnRequestingTillRunning(peer, msg)
          }

        RPConfAsk[F].ask >>= { conf =>
          val msg = packet(conf.local, conf.networkId, message)
          Log[F].info(s"Starting to request ${msgTypeName}") >>
            keepOnRequestingTillRunning(peer, msg).void
        }
      }

      def requestForBlock(
          peer: PeerNode,
          hash: BlockHash
      ): F[Unit] =
        Log[F].debug(
          s"Requesting ${PrettyPrinter.buildString(hash)} from ${peer.endpoint.host}. "
        ) >> TransportLayer[F].sendToPeer(peer, ToPacket(BlockRequestProto(hash)))
    }
}

trait CommUtilSyntax {
  implicit final def casperSyntaxCommUtil[F[_]](commUtil: CommUtil[F]): CommUtilOps[F] =
    new CommUtilOps[F](commUtil)
}

final class CommUtilOps[F[_]](
    // CommUtil extensions / syntax
    private val commUtil: CommUtil[F]
) {
  def sendToPeers[Msg: ToPacket](message: Msg, scopeSize: Option[Int] = None): F[Unit] =
    commUtil.sendToPeers(ToPacket(message), scopeSize)

  def streamToPeers[Msg: ToPacket](message: Msg, scopeSize: Option[Int] = None): F[Unit] =
    commUtil.streamToPeers(ToPacket(message), scopeSize)

  def sendBlockHash(
      hash: BlockHash,
      blockCreator: ByteString
  )(implicit m: Monad[F], log: Log[F]): F[Unit] =
    sendToPeers(BlockHashMessageProto(hash, blockCreator)) >>
      Log[F].info(s"Sent hash ${PrettyPrinter.buildString(hash)} to peers")

  def broadcastHasBlockRequest(hash: BlockHash): F[Unit] =
    sendToPeers(HasBlockRequestProto(hash))

  def broadcastRequestForBlock(hash: BlockHash, scopeSize: Option[Int] = None): F[Unit] =
    sendToPeers(BlockRequest(hash).toProto, scopeSize)

  def sendForkChoiceTipRequest(implicit m: Monad[F], log: Log[F]): F[Unit] =
    sendToPeers(ForkChoiceTipRequest.toProto) >>
      Log[F].info(s"Requested fork tip from peers")

  def requestFinalizedFringe(
      trimState: Boolean = true
  )(implicit m: Sync[F], r: RPConfAsk[F]): F[Unit] =
    for {
      maybeBootstrap <- RPConfAsk[F].reader(_.bootstrap)
      bootstrap      <- maybeBootstrap.liftTo(StandaloneNodeSendToBootstrapError)
      msg            = FinalizedFringeRequest("", trimState).toProto
      _              <- commUtil.sendWithRetry(ToPacket(msg), bootstrap, 10.seconds, "FinalizedFringeRequest")
    } yield ()

  def sendStoreItemsRequest(
      req: StoreItemsMessageRequest
  ): F[Unit] =
    sendToPeers(StoreItemsMessageRequest.toProto(req))

  def sendStoreItemsRequest(
      rootStateHash: Blake2b256Hash,
      pageSize: Int
  ): F[Unit] = {
    val rootPath = Seq((rootStateHash, none[Byte]))
    val req      = StoreItemsMessageRequest(rootPath, 0, pageSize)
    sendStoreItemsRequest(req)
  }
}
