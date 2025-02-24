package io.rhonix.rspace.history

import cats.Parallel
import cats.effect.Concurrent
import cats.syntax.all._
import io.rhonix.metrics.Span
import io.rhonix.rspace.hashing.Blake2b256Hash
import io.rhonix.rspace.serializers.ScodecSerialize.{DatumB, JoinsB, WaitingContinuationB}
import io.rhonix.rspace.state.instances.{RSpaceExporterStore, RSpaceImporterStore}
import io.rhonix.rspace.state.{RSpaceExporter, RSpaceImporter}
import io.rhonix.rspace.{HotStoreAction, HotStoreTrieAction}
import io.rhonix.shared.{Log, Serialize}
import io.rhonix.store.{KeyValueStore, LazyAdHocKeyValueCache}

/**
  * Pointer to data in history (Datums, Continuations or Joins)
  * @param state - state hash
  * @param hash - hash of a leaf
  */
final case class HistoryPointer(state: Blake2b256Hash, hash: Blake2b256Hash)

/**
  * Cache of decoded values from history
  */
final case class HistoryCache[F[_], C, P, A, K](
    dtsCache: LazyAdHocKeyValueCache[F, HistoryPointer, Seq[DatumB[A]]],
    wksCache: LazyAdHocKeyValueCache[F, HistoryPointer, Seq[WaitingContinuationB[P, K]]],
    jnsCache: LazyAdHocKeyValueCache[F, HistoryPointer, Seq[JoinsB[C]]]
)

trait HistoryRepository[F[_], C, P, A, K] {
  def checkpoint(actions: List[HotStoreAction]): F[HistoryRepository[F, C, P, A, K]]

  def doCheckpoint(actions: Seq[HotStoreTrieAction]): F[HistoryRepository[F, C, P, A, K]]

  def reset(root: Blake2b256Hash): F[HistoryRepository[F, C, P, A, K]]

  def history: History[F]

  def exporter: F[RSpaceExporter[F]]

  def importer: F[RSpaceImporter[F]]

  def getHistoryReader(stateHash: Blake2b256Hash): F[HistoryReader[F, Blake2b256Hash, C, P, A, K]]

  def getSerializeC: Serialize[C]

  def root: Blake2b256Hash
}

object HistoryRepositoryInstances {

  val PREFIX_DATUM: Byte = 0x00
  val PREFIX_KONT: Byte  = 0x01
  val PREFIX_JOINS: Byte = 0x02

  def lmdbRepository[F[_]: Concurrent: Parallel: Log: Span, C, P, A, K](
      historyKeyValueStore: KeyValueStore[F],
      rootsKeyValueStore: KeyValueStore[F],
      coldKeyValueStore: KeyValueStore[F]
  )(
      implicit
      sc: Serialize[C],
      sp: Serialize[P],
      sa: Serialize[A],
      sk: Serialize[K]
  ): F[HistoryRepository[F, C, P, A, K]] = {
    // Roots store
    val rootsRepository = new RootRepository[F](
      RootsStoreInstances.rootsStore[F](rootsKeyValueStore)
    )
    for {
      currentRoot <- rootsRepository.currentRoot()

      // History store
      history <- History.create(currentRoot, historyKeyValueStore)

      // Cold store
      coldStore = ColdStoreInstances.coldStore[F](coldKeyValueStore)
      // RSpace importer/exporter / directly operates on Store (lmdb)
      exporter = RSpaceExporterStore[F](historyKeyValueStore, coldKeyValueStore, rootsKeyValueStore)
      importer = RSpaceImporterStore[F](historyKeyValueStore, coldKeyValueStore, rootsKeyValueStore)
    } yield HistoryRepositoryImpl[F, C, P, A, K](
      history,
      rootsRepository,
      coldStore,
      exporter,
      importer,
      sc,
      sp,
      sa,
      sk
    )
  }
}
