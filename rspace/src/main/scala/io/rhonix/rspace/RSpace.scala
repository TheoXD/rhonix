package io.rhonix.rspace

import cats.Parallel
import cats.effect._
import cats.syntax.all._
import com.typesafe.scalalogging.Logger
import io.rhonix.catscontrib._
import io.rhonix.metrics.Metrics.Source
import io.rhonix.metrics.implicits._
import io.rhonix.metrics.{Metrics, Span}
import io.rhonix.rspace.history._
import io.rhonix.rspace.internal.{ConsumeCandidate, Datum, ProduceCandidate, WaitingContinuation}
import io.rhonix.rspace.trace._
import io.rhonix.shared.{Log, Serialize}
import io.rhonix.store.KeyValueStore
import monix.execution.atomic.AtomicAny

import scala.collection.SortedSet
import scala.concurrent.ExecutionContext

class RSpace[F[_]: Concurrent: ContextShift: Log: Metrics: Span, C, P, A, K](
    historyRepository: HistoryRepository[F, C, P, A, K],
    storeAtom: AtomicAny[HotStore[F, C, P, A, K]]
)(
    implicit
    serializeC: Serialize[C],
    serializeP: Serialize[P],
    serializeA: Serialize[A],
    serializeK: Serialize[K],
    val m: Match[F, P, A],
    scheduler: ExecutionContext
) extends RSpaceOps[F, C, P, A, K](historyRepository, storeAtom)
    with ISpace[F, C, P, A, K] {

  protected[this] override val logger: Logger = Logger[this.type]

  implicit protected[this] lazy val MetricsSource: Source = RSpaceMetricsSource

  protected[this] override def lockedConsume(
      channels: Seq[C],
      patterns: Seq[P],
      continuation: K,
      persist: Boolean,
      peeks: SortedSet[Int],
      consumeRef: Consume
  ): F[MaybeActionResult] =
    Span[F].traceI("locked-consume") {
      for {
        _ <- Log[F].debug(
              s"consume: searching for data matching <patterns: $patterns> at <channels: $channels>"
            )
        _                    <- logConsume(consumeRef, channels, patterns, continuation, persist, peeks)
        channelToIndexedData <- fetchChannelToIndexData(channels)
        options <- extractDataCandidates(
                    channels.zip(patterns),
                    channelToIndexedData,
                    Nil
                  ).map(_.sequence)
        wk = WaitingContinuation(patterns, continuation, persist, peeks, consumeRef)
        result <- options.fold(storeWaitingContinuation(channels, wk))(
                   dataCandidates =>
                     for {
                       comm <- COMM(dataCandidates, consumeRef, peeks, produceCounters _)
                       _    <- logComm(dataCandidates, channels, wk, comm, consumeCommLabel)
                       _    <- storePersistentData(dataCandidates, peeks)
                       _ <- Log[F].debug(
                             s"consume: data found for <patterns: $patterns> at <channels: $channels>"
                           )
                     } yield wrapResult(channels, wk, consumeRef, dataCandidates)
                 )
      } yield result
    }

  /*
   * Here, we create a cache of the data at each channel as `channelToIndexedData`
   * which is used for finding matches.  When a speculative match is found, we can
   * remove the matching datum from the remaining data candidates in the cache.
   *
   * Put another way, this allows us to speculatively remove matching data without
   * affecting the actual store contents.
   */
  private[this] def fetchChannelToIndexData(channels: Seq[C]): F[Map[C, Seq[(Datum[A], Int)]]] =
    channels
      .traverse { c: C =>
        store.getData(c).shuffleWithIndex.map(c -> _)
      }
      .map(_.toMap)

  protected[this] override def lockedProduce(
      channel: C,
      data: A,
      persist: Boolean,
      produceRef: Produce
  ): F[MaybeActionResult] =
    Span[F].traceI("locked-produce") {
      for {
        //TODO fix double join fetch
        groupedChannels <- store.getJoins(channel)
        _ <- Log[F].debug(
              s"produce: searching for matching continuations at <groupedChannels: $groupedChannels>"
            )
        _ <- logProduce(produceRef, channel, data, persist)
        extracted <- extractProduceCandidate(
                      groupedChannels,
                      channel,
                      Datum(data, persist, produceRef)
                    )
        r <- extracted.fold(storeData(channel, data, persist, produceRef))(processMatchFound)
      } yield r
    }

  /*
   * Find produce candidate
   */
  private[this] def extractProduceCandidate(
      groupedChannels: Seq[CandidateChannels],
      batChannel: C,
      data: Datum[A]
  ): F[MaybeProduceCandidate] =
    runMatcherForChannels(
      groupedChannels,
      channels =>
        store
          .getContinuations(channels)
          .shuffleWithIndex,
      /*
       * Here, we create a cache of the data at each channel as `channelToIndexedData`
       * which is used for finding matches.  When a speculative match is found, we can
       * remove the matching datum from the remaining data candidates in the cache.
       *
       * Put another way, this allows us to speculatively remove matching data without
       * affecting the actual store contents.
       *
       * In this version, we also add the produced data directly to this cache.
       */
      c =>
        store
          .getData(c)
          .shuffleWithIndex
          .map { d =>
            if (c == batChannel) (data, -1) +: d else d
          }
          .map(c -> _)
    )

  private[this] def processMatchFound(
      pc: ProduceCandidate[C, P, A, K]
  ): F[MaybeActionResult] = {
    val ProduceCandidate(
      channels,
      wk @ WaitingContinuation(_, _, persistK, peeks, consumeRef),
      continuationIndex,
      dataCandidates
    ) = pc

    for {
      comm <- COMM(dataCandidates, consumeRef, peeks, produceCounters _)
      _    <- logComm(dataCandidates, channels, wk, comm, produceCommLabel)
      _    <- store.removeContinuation(channels, continuationIndex).unlessA(persistK)
      _    <- removeMatchedDatumAndJoin(channels, dataCandidates)
      _    <- Log[F].debug(s"produce: matching continuation found at <channels: $channels>")
    } yield wrapResult(channels, wk, consumeRef, dataCandidates)
  }

  protected override def logComm(
      dataCandidates: Seq[ConsumeCandidate[C, A]],
      channels: Seq[C],
      wk: WaitingContinuation[P, K],
      comm: COMM,
      label: String
  ): F[COMM] =
    for {
      _ <- Metrics[F].incrementCounter(label)
      _ <- eventLog.update(comm +: _)
    } yield comm

  protected override def logConsume(
      consumeRef: Consume,
      channels: Seq[C],
      patterns: Seq[P],
      continuation: K,
      persist: Boolean,
      peeks: SortedSet[Int]
  ): F[Consume] = eventLog.update(consumeRef +: _).as(consumeRef)

  protected override def logProduce(
      produceRef: Produce,
      channel: C,
      data: A,
      persist: Boolean
  ): F[Produce] =
    for {
      _ <- eventLog.update(produceRef +: _)
      _ <- produceCounter.update(_.putAndIncrementCounter(produceRef)).whenA(!persist)
    } yield produceRef

  override def createCheckpoint(): F[Checkpoint] = spanF.withMarks("create-checkpoint") {
    for {
      changes <- spanF.withMarks("changes") { storeAtom.get().changes }
      nextHistory <- spanF.withMarks("history-checkpoint") {
                      historyRepositoryAtom.get().checkpoint(changes.toList)
                    }
      _             = historyRepositoryAtom.set(nextHistory)
      log           <- eventLog.getAndSet(Seq.empty)
      _             <- produceCounter.set(Map.empty.withDefaultValue(0))
      historyReader <- nextHistory.getHistoryReader(nextHistory.root)
      _             <- createNewHotStore(historyReader)
      _             <- restoreInstalls()
    } yield Checkpoint(nextHistory.history.root, log)
  }

  def spawn: F[ISpace[F, C, P, A, K]] = spanF.withMarks("spawn") {
    for {
      historyRepo   <- Sync[F].delay(historyRepositoryAtom.get())
      nextHistory   <- historyRepo.reset(historyRepo.history.root)
      historyReader <- nextHistory.getHistoryReader(nextHistory.root)
      hotStore      <- HotStore(historyReader.base)
      rSpace        <- RSpace(nextHistory, hotStore)
      _             <- rSpace.restoreInstalls()
    } yield rSpace
  }
}

object RSpace {

  /**
    * Maps (key-value stores) used to create [[RSpace]] or [[ReplayRSpace]].
    */
  final case class RSpaceStore[F[_]](
      history: KeyValueStore[F],
      roots: KeyValueStore[F],
      cold: KeyValueStore[F]
  )

  /**
    * Creates [[RSpace]] from [[HistoryRepository]] and [[HotStore]].
    */
  def apply[F[_]: Concurrent: ContextShift: Span: Metrics: Log, C, P, A, K](
      historyRepository: HistoryRepository[F, C, P, A, K],
      store: HotStore[F, C, P, A, K]
  )(
      implicit
      sc: Serialize[C],
      sp: Serialize[P],
      sa: Serialize[A],
      sk: Serialize[K],
      m: Match[F, P, A],
      scheduler: ExecutionContext
  ): F[RSpace[F, C, P, A, K]] =
    Sync[F].delay(new RSpace[F, C, P, A, K](historyRepository, AtomicAny(store)))

  /**
    * Creates [[RSpace]] from [[KeyValueStore]]'s,
    */
  def create[F[_]: Concurrent: Parallel: ContextShift: Span: Metrics: Log, C, P, A, K](
      store: RSpaceStore[F]
  )(
      implicit
      sc: Serialize[C],
      sp: Serialize[P],
      sa: Serialize[A],
      sk: Serialize[K],
      m: Match[F, P, A],
      scheduler: ExecutionContext
  ): F[RSpace[F, C, P, A, K]] =
    for {
      setup                  <- createHistoryRepo[F, C, P, A, K](store)
      (historyReader, store) = setup
      space                  <- RSpace(historyReader, store)
    } yield space

  /**
    * Creates [[RSpace]] and [[ReplayRSpace]] from [[KeyValueStore]]'s.
    */
  def createWithReplay[F[_]: Concurrent: Parallel: ContextShift: Span: Metrics: Log, C, P, A, K](
      store: RSpaceStore[F]
  )(
      implicit sc: Serialize[C],
      sp: Serialize[P],
      sa: Serialize[A],
      sk: Serialize[K],
      m: Match[F, P, A],
      scheduler: ExecutionContext
  ): F[(RSpace[F, C, P, A, K], ReplayRSpace[F, C, P, A, K])] =
    for {
      setup                <- createHistoryRepo[F, C, P, A, K](store)
      (historyRepo, store) = setup
      // Play
      space <- RSpace(historyRepo, store)
      // Replay
      historyReader <- historyRepo.getHistoryReader(historyRepo.root)
      replayStore   <- HotStore(historyReader.base)
      replay        <- ReplayRSpace(historyRepo, replayStore)
    } yield (space, replay)

  /**
    * Creates [[HistoryRepository]] and [[HotStore]].
    */
  def createHistoryRepo[F[_]: Concurrent: Parallel: Log: Span, C, P, A, K](store: RSpaceStore[F])(
      implicit
      sc: Serialize[C],
      sp: Serialize[P],
      sa: Serialize[A],
      sk: Serialize[K]
  ): F[(HistoryRepository[F, C, P, A, K], HotStore[F, C, P, A, K])] = {
    import io.rhonix.rspace.history._

    for {
      historyRepo <- HistoryRepositoryInstances.lmdbRepository[F, C, P, A, K](
                      store.history,
                      store.roots,
                      store.cold
                    )
      historyReader <- historyRepo.getHistoryReader(historyRepo.root)
      hotStore      <- HotStore(historyReader.base)
    } yield (historyRepo, hotStore)
  }
}
