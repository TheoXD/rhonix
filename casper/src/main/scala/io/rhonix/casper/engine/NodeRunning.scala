package io.rhonix.casper.engine

import cats.Monad
import cats.effect.{Concurrent, Sync}
import cats.syntax.all._
import com.google.protobuf.ByteString
import io.rhonix.blockstorage.BlockStore
import io.rhonix.blockstorage.BlockStore.BlockStore
import io.rhonix.blockstorage.dag.BlockDagStorage
import io.rhonix.casper._
import io.rhonix.casper.blocks.{BlockReceiver, BlockRetriever}
import io.rhonix.casper.protocol.{CommUtil, _}
import io.rhonix.casper.syntax._
import io.rhonix.comm.PeerNode
import io.rhonix.comm.rp.Connect.{ConnectionsCell, RPConfAsk}
import io.rhonix.comm.transport.TransportLayer
import io.rhonix.metrics.Metrics
import io.rhonix.models.BlockHash.BlockHash
import io.rhonix.rspace.hashing.Blake2b256Hash
import io.rhonix.rspace.state.{RSpaceExporter, RSpaceStateManager}
import io.rhonix.shared.syntax._
import io.rhonix.shared.{Log, Time}
import fs2.concurrent.Queue

object NodeRunning {

  // format: off
  def apply[F[_]
  /* Execution */   : Concurrent: Time
  /* Transport */   : TransportLayer: CommUtil: BlockRetriever
  /* State */       : RPConfAsk: ConnectionsCell
  /* Storage */     : BlockStore: BlockDagStorage: RSpaceStateManager
  /* Diagnostics */ : Log: Metrics] // format: on
  (
      blockProcessingQueue: Queue[F, BlockMessage],
      validatorId: Option[ValidatorIdentity],
      disableStateExporter: Boolean
  ): F[NodeRunning[F]] = Sync[F].delay(
    new NodeRunning[F](
      blockProcessingQueue,
      validatorId,
      disableStateExporter
    )
  )

  implicit val MetricsSource: Metrics.Source =
    Metrics.Source(CasperMetricsSource, "running")

  trait CasperMessageStatus
  final case object BlockIsInDag            extends CasperMessageStatus
  final case object BlockIsInCasperBuffer   extends CasperMessageStatus
  final case object BlockIsReceived         extends CasperMessageStatus
  final case object BlockIsWaitingForCasper extends CasperMessageStatus
  final case object BlockIsInProcessing     extends CasperMessageStatus
  final case object DoNotIgnore             extends CasperMessageStatus

  final case class IgnoreCasperMessageStatus(doIgnore: Boolean, status: CasperMessageStatus)

  /**
    * Peer broadcasted block hash.
    */
  def handleBlockHashMessage[F[_]: Monad: BlockRetriever: Log](
      peer: PeerNode,
      bhm: BlockHashMessage
  )(
      ignoreMessageF: BlockHash => F[Boolean]
  ): F[Unit] = {
    val h = bhm.blockHash
    def logIgnore = Log[F].debug(
      s"Ignoring ${PrettyPrinter.buildString(h)} hash broadcast"
    )
    val logSuccess = Log[F].debug(
      s"Incoming BlockHashMessage ${PrettyPrinter.buildString(h)} " +
        s"from ${peer.endpoint.host}"
    )
    val processHash =
      BlockRetriever[F].admitHash(h, peer.some, BlockRetriever.HashBroadcastRecieved)

    ignoreMessageF(h).ifM(
      logIgnore,
      logSuccess >> processHash.void
    )
  }

  /**
    * Peer says it has particular block.
    */
  def handleHasBlockMessage[F[_]: Monad: BlockRetriever: Log](
      peer: PeerNode,
      blockHash: BlockHash
  )(
      ignoreMessageF: BlockHash => F[Boolean]
  ): F[Unit] = {
    def logIgnore = Log[F].debug(
      s"Ignoring ${PrettyPrinter.buildString(blockHash)} HasBlockMessage"
    )
    val logProcess = Log[F].debug(
      s"Incoming HasBlockMessage ${PrettyPrinter.buildString(blockHash)} from ${peer.endpoint.host}"
    )
    val processHash =
      BlockRetriever[F].admitHash(blockHash, peer.some, BlockRetriever.HasBlockMessageReceived)

    ignoreMessageF(blockHash).ifM(
      logIgnore,
      logProcess >> processHash.void
    )
  }

  /**
    * Peer asks for particular block
    */
  def handleBlockRequest[F[_]: Monad: TransportLayer: RPConfAsk: BlockStore: Log](
      peer: PeerNode,
      br: BlockRequest
  ): F[Unit] = {
    val getBlock = BlockStore[F].get1(br.hash).flatMap(_.get.pure[F])
    val logSuccess = Log[F].info(
      s"Received request for block ${PrettyPrinter.buildString(br.hash)} " +
        s"from $peer. Response sent."
    )
    val logError = Log[F].info(
      s"Received request for block ${PrettyPrinter.buildString(br.hash)} " +
        s"from $peer. No response given since block not found."
    )
    def sendResponse(block: BlockMessage) =
      TransportLayer[F].streamToPeer(peer, block.toProto)
    val hasBlock = BlockStore[F].contains(br.hash)

    hasBlock.ifM(
      logSuccess >> getBlock >>= sendResponse,
      logError
    )
  }

  /**
    * Peer asks if this node has particular block
    */
  def handleHasBlockRequest[F[_]: Monad: TransportLayer: RPConfAsk](
      peer: PeerNode,
      hbr: HasBlockRequest
  )(blockLookup: BlockHash => F[Boolean]): F[Unit] = {
    val hasBlock  = blockLookup(hbr.hash)
    val sendBlock = TransportLayer[F].sendToPeer(peer, HasBlockProto(hbr.hash))

    hasBlock.ifM(sendBlock, ().pure[F])
  }

  /**
    * Peer asks for fork-choice tip
    */
  // TODO name for this message is misleading, as its a request for all tips, not just fork choice.
  def handleForkChoiceTipRequest[F[_]: Sync: TransportLayer: RPConfAsk: BlockStore: BlockDagStorage: Log](
      peer: PeerNode
  ): F[Unit] = {
    val logRequest = Log[F].info(s"Received ForkChoiceTipRequest from ${peer.endpoint.host}")
    def logResponse(tips: Seq[BlockHash]): F[Unit] =
      Log[F].info(
        s"Sending tips ${PrettyPrinter.buildString(tips)} to ${peer.endpoint.host}"
      )
    val getTips =
      BlockDagStorage[F].getRepresentation.map(_.dagMessageState.latestMsgs.map(_.id).toList)
    // TODO respond with all tips in a single message
    def respondToPeer(tip: BlockHash) = TransportLayer[F].sendToPeer(peer, HasBlockProto(tip))

    logRequest >> getTips >>= { t =>
      t.traverse(respondToPeer) >> logResponse(t)
    }
  }

  /**
    * Peer asks for FinalizedFringe
    */
  def handleFinalizedFringeRequest[F[_]: Monad: TransportLayer: RPConfAsk: Log](
      peer: PeerNode,
      finalizedFringe: FinalizedFringe
  ): F[Unit] =
    Log[F].info(s"Received FinalizedFringeRequest from ${peer}") >>
      TransportLayer[F].streamToPeer(peer, finalizedFringe.toProto) >>
      Log[F].info(s"FinalizedFringe sent to ${peer}")

  private def handleStateItemsMessageRequest[F[_]: Sync: TransportLayer: RPConfAsk: RSpaceStateManager: Log](
      peer: PeerNode,
      startPath: Seq[(Blake2b256Hash, Option[Byte])],
      skip: Int,
      take: Int
  ): F[Unit] = {
    import io.rhonix.rspace.syntax._
    for {
      // Export chunk of store items from RSpace
      exportedItems <- RSpaceStateManager[F].exporter.getHistoryAndData(
                        startPath,
                        skip,
                        take,
                        ByteString.copyFrom
                      )

      (history, data) = exportedItems

      // Prepare response with the chunk of store items
      resp = StoreItemsMessage(startPath, history.lastPath, history.items, data.items)

      _ <- Log[F].info(s"Read ${resp.pretty}")

      // Stream store items to peer
      respProto = StoreItemsMessage.toProto(resp)
      _         <- TransportLayer[F].streamToPeer(peer, respProto)

      _ <- Log[F].info(s"Store items sent to $peer")
    } yield ()
  }
}

// format: off
class NodeRunning[F[_]
  /* Execution */   : Concurrent: Time
  /* Transport */   : TransportLayer: CommUtil: BlockRetriever
  /* State */       : RPConfAsk: ConnectionsCell
  /* Storage */     : BlockStore: BlockDagStorage: RSpaceStateManager
  /* Diagnostics */ : Log: Metrics] // format: on
(
    incomingBlocksQueue: Queue[F, BlockMessage],
    validatorId: Option[ValidatorIdentity],
    disableStateExporter: Boolean
) {
  import NodeRunning._

  /**
    * Check if block is stored in the BlockStore
    */
  private def checkBlockReceived(hash: BlockHash): F[Boolean] =
    BlockStore[F].contains(hash)

  def handle(peer: PeerNode, msg: CasperMessage): F[Unit] = msg match {
    case h: BlockHashMessage =>
      handleBlockHashMessage(peer, h)(checkBlockReceived)

    case b: BlockMessage =>
      for {
        _ <- validatorId match {
              case None => ().pure[F]
              case Some(id) =>
                Log[F]
                  .warn(
                    s"There is another node $peer proposing using the same private key as you. " +
                      s"Or did you restart your node?"
                  )
                  .whenA(b.sender == ByteString.copyFrom(id.publicKey.bytes))
            }
        _ <- checkBlockReceived(b.blockHash).ifM(
              Log[F].debug(
                s"Ignoring BlockMessage ${PrettyPrinter.buildString(b, short = true)} " +
                  s"from ${peer.endpoint.host}"
              ),
              incomingBlocksQueue.enqueue1(b) <* Log[F].debug(
                s"Incoming BlockMessage ${PrettyPrinter.buildString(b, short = true)} " +
                  s"from ${peer.endpoint.host}"
              )
            )
      } yield ()

    case br: BlockRequest => handleBlockRequest(peer, br)

    case hbr: HasBlockRequest =>
      // Return blocks only available in the DAG (validated)
      // - blocks can be returned from BlockStore if downloaded from latest in the DAG and not from tips
      for {
        dag <- BlockDagStorage[F].getRepresentation
        res <- handleHasBlockRequest(peer, hbr)(dag.contains(_).pure[F])
      } yield res
    case HasBlock(blockHash) =>
      val processKnownBlock =
        for {
          blockNotValidated <- BlockReceiver.notValidated(blockHash)
          _ <- (BlockStore[F].getUnsafe(blockHash) >>= incomingBlocksQueue.enqueue1)
                .whenA(blockNotValidated)
        } yield ()
      val logProcess = Log[F].debug(
        s"Incoming HasBlockMessage ${PrettyPrinter.buildString(blockHash)} from ${peer.endpoint.host}"
      )
      val requestUnknownBlock = BlockRetriever[F]
        .admitHash(blockHash, peer.some, BlockRetriever.HasBlockMessageReceived)

      checkBlockReceived(blockHash).ifM(processKnownBlock, logProcess >> requestUnknownBlock.void)

    case ForkChoiceTipRequest => handleForkChoiceTipRequest(peer)

    case FinalizedFringeRequest(_, _) =>
      for {
        dag <- BlockDagStorage[F].getRepresentation

        // Respond with latest finalized fringe
        // TODO: optimize response to read from cache
        latestFringeHashes    = dag.dagMessageState.latestFringe.map(_.id)
        fringeData            = dag.fringeStates(latestFringeHashes)
        latestFringeStateHash = fringeData.stateHash
        fringeResponse = FinalizedFringe(
          latestFringeHashes.toList,
          latestFringeStateHash.toByteString
        )

        _ <- handleFinalizedFringeRequest(peer, fringeResponse)

        fringeStr      = PrettyPrinter.buildString(fringeResponse.hashes)
        fringeStateStr = PrettyPrinter.buildString(latestFringeStateHash.toByteString)
        _              <- Log[F].info(s"Sent fringe response ($fringeStateStr) $fringeStr.")
      } yield ()

    // Approved state store records
    case StoreItemsMessageRequest(startPath, skip, take) =>
      val start = startPath.map(RSpaceExporter.pathPretty).mkString(" ")
      val logRequest = Log[F].info(
        s"Received request for store items, startPath: [$start], chunk: $take, skip: $skip, from: $peer"
      )
      if (!disableStateExporter) {
        logRequest *> handleStateItemsMessageRequest(peer, startPath, skip, take)
      } else {
        Log[F].info(
          s"Received StoreItemsMessage request but the node is configured to not respond to StoreItemsMessage, from ${peer}."
        )
      }

    case _ => ().pure[F]
  }
}
