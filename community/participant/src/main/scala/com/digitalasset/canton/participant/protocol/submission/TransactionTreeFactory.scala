// Copyright (c) 2026 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.participant.protocol.submission

import cats.data.EitherT
import com.digitalasset.canton.*
import com.digitalasset.canton.crypto.{HashOps, HmacOps, Salt, SaltSeed}
import com.digitalasset.canton.data.{
  CantonTimestamp,
  GenTransactionTree,
  TransactionView,
  ViewParticipantData,
  ViewPosition,
}
import com.digitalasset.canton.ledger.participant.state.SubmitterInfo
import com.digitalasset.canton.lifecycle.FutureUnlessShutdown
import com.digitalasset.canton.lifecycle.FutureUnlessShutdownImpl.*
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.participant.protocol.submission.TransactionTreeFactory.{
  ContractInstanceOfId,
  TransactionTreeConversionError,
}
import com.digitalasset.canton.participant.store.ContractLookup
import com.digitalasset.canton.protocol.WellFormedTransaction.{
  WithAbsoluteSuffixes,
  WithoutSuffixes,
}
import com.digitalasset.canton.protocol.{GenContractInstance, *}
import com.digitalasset.canton.sequencing.protocol.MediatorGroupRecipient
import com.digitalasset.canton.topology.client.TopologySnapshot
import com.digitalasset.canton.topology.{ParticipantId, PhysicalSynchronizerId}
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.{ContractHasher, LfTransactionUtil}
import com.digitalasset.canton.version.ProtocolVersion
import com.digitalasset.daml.lf.data.ImmArray
import com.digitalasset.daml.lf.transaction.LegacyTransactionErrors

import java.util.UUID
import scala.annotation.tailrec
import scala.collection.mutable
import scala.concurrent.ExecutionContext

trait TransactionTreeFactory {

  /** The [[com.digitalasset.canton.protocol.CantonContractIdVersion]] to be used for newly created
    * contracts
    */
  def cantonContractIdVersion: CantonContractIdVersion

  /** Converts a `transaction: LfTransaction` to the corresponding transaction tree, if possible.
    *
    * @param legacyKeyResolver
    *   The key resolutions recorded while interpreting the transaction.
    * @see
    *   TransactionTreeConversionError for error cases
    */
  def createTransactionTree(
      transaction: WellFormedTransaction[WithoutSuffixes],
      submitterInfo: SubmitterInfo,
      workflowId: Option[WorkflowId],
      mediator: MediatorGroupRecipient,
      transactionSeed: SaltSeed,
      transactionUuid: UUID,
      topologySnapshot: TopologySnapshot,
      contractOfId: ContractInstanceOfId,
      // TODO(#31527): SPM always empty in 3.4, not used in 3.5 => to remove
      legacyKeyResolver: LfGlobalKeyMapping,
      maxSequencingTime: CantonTimestamp,
      validatePackageVettings: Boolean,
  )(implicit
      traceContext: TraceContext
  ): EitherT[FutureUnlessShutdown, TransactionTreeConversionError, GenTransactionTree]

  /** Reconstructs a transaction view from a reinterpreted action description, using the supplied
    * salts.
    *
    * @param legacyKeyResolver
    *   The key resolutions recorded while re-interpreting the transaction.
    * @throws java.lang.IllegalArgumentException
    *   if `transaction` does not contain exactly one root node
    */
  def tryReconstruct(
      transaction: WellFormedTransaction[WithoutSuffixes],
      rootPosition: ViewPosition,
      mediator: MediatorGroupRecipient,
      submittingParticipantO: Option[ParticipantId],
      salts: Iterable[Salt],
      transactionUuid: UUID,
      topologySnapshot: TopologySnapshot,
      contractOfId: ContractInstanceOfId,
      rbContext: RollbackContext,
      // TODO(#31527): SPM always empty in 3.4, not used in 3.5 => to remove
      legacyKeyResolver: LfGlobalKeyMapping,
      absolutizer: ContractIdAbsolutizer,
  )(implicit traceContext: TraceContext): EitherT[
    FutureUnlessShutdown,
    TransactionTreeConversionError,
    (TransactionView, WellFormedTransaction[WithAbsoluteSuffixes]),
  ]

  /** Extracts the salts for the view from a transaction view tree. The salts appear in the same
    * order as they are needed by [[tryReconstruct]].
    */
  def saltsFromView(view: TransactionView): Iterable[Salt]

}

object TransactionTreeFactory {

  type ContractInstanceOfId =
    LfContractId => EitherT[FutureUnlessShutdown, ContractLookupError, GenContractInstance]

  private[submission] def nodeIdNormalizationForView(
      transaction: LfVersionedTransaction,
      viewRootNodeId: LfNodeId,
  ): Map[LfNodeId, LfNodeId] = {
    val nodes = Map.newBuilder[LfNodeId, LfNode]

    @tailrec
    def go(toVisit: List[LfNodeId]): Unit =
      toVisit match {
        case Nil =>
        case nodeId :: rest =>
          val node = transaction.nodes.getOrElse(
            nodeId,
            throw new IllegalStateException(s"Did not find $nodeId in node map"),
          )
          nodes += nodeId -> node
          val children = node match {
            case exercise: LfNodeExercises => exercise.children.toList
            case rollback: LfNodeRollback => rollback.children.toList
            case _ => List.empty
          }
          go(children ++ rest)
      }

    go(List(viewRootNodeId))

    LfVersionedTransaction(
      transaction.version,
      nodes.result(),
      ImmArray(viewRootNodeId),
    ).nodeIdNormalization
  }

  private[submission] def externalCallResultsFromCoreNodes(
      coreOtherNodes: List[(LfNodeId, LfActionNode, RollbackContext.RollbackScope)],
      childViews: Seq[TransactionView],
      normalizeNodeId: LfNodeId => LfNodeId,
      originalRootNodeIds: Set[LfNodeId],
      submittingAdminPartyO: Option[LfPartyId],
  ): ImmArray[ViewParticipantData.ViewExternalCallResult] = {
    val coreExternalCallResults = coreOtherNodes.flatMap {
      case (nodeId, exercise: LfNodeExercises, _) =>
        exercise.externalCallResults.toSeq.zipWithIndex.map { case (result, callIndex) =>
          ViewParticipantData.ViewExternalCallResult(
            result = result,
            nodeId = normalizeNodeId(nodeId),
            callIndex = callIndex,
            checkingParties = checkingPartiesForNode(
              nodeId,
              exercise,
              originalRootNodeIds,
              submittingAdminPartyO,
            ),
          )
        }
      case _ => Seq.empty
    }

    suppressExternalCallResultsCoveredBySubviews(coreExternalCallResults, childViews)
  }

  private def checkingPartiesForNode(
      nodeId: LfNodeId,
      node: LfActionNode,
      originalRootNodeIds: Set[LfNodeId],
      submittingAdminPartyO: Option[LfPartyId],
  ): Set[LfPartyId] =
    Option
      .when(originalRootNodeIds.contains(nodeId))(submittingAdminPartyO)
      .flatten
      .fold[Set[LfPartyId]](Set.empty)(party => Set(party)) |
      LfTransactionUtil.signatoriesOrMaintainers(node) |
      LfTransactionUtil.actingParties(node)

  private def suppressExternalCallResultsCoveredBySubviews(
      coreExternalCallResults: Seq[ViewParticipantData.ViewExternalCallResult],
      childViews: Seq[TransactionView],
  ): ImmArray[ViewParticipantData.ViewExternalCallResult] = {
    val coveredBySubviews = mutable.ArrayBuffer.from(
      childViews
        .flatMap(_.flatten)
        .flatMap(_.viewParticipantData.tryUnwrap.externalCallResults.toSeq)
    )
    val retained = coreExternalCallResults.filterNot { externalCallResult =>
      val coveredIndex = coveredBySubviews.indexWhere(externalCallResult.isCoveredBy)
      if (coveredIndex >= 0) {
        val _ = coveredBySubviews.remove(coveredIndex)
        true
      } else false
    }

    ImmArray.from(retained)
  }

  private[submission] def originalRootNodeIdsForReconstruction(
      transaction: LfVersionedTransaction,
      rootPosition: ViewPosition,
  ): Set[LfNodeId] =
    if (rootPosition.reverse.isTopLevel) transaction.roots.toSeq.toSet else Set.empty

  private[submission] def submittingAdminPartyForReconstruction(
      submittingParticipantO: Option[ParticipantId],
      rootPosition: ViewPosition,
  ): Option[LfPartyId] =
    Option
      .when(rootPosition.reverse.isTopLevel)(submittingParticipantO)
      .flatten
      .map(_.adminParty.toLf)

  def apply(
      submittingParticipant: ParticipantId,
      synchronizerId: PhysicalSynchronizerId,
      cantonContractIdVersion: CantonContractIdVersion,
      cryptoOps: HashOps & HmacOps,
      hasher: ContractHasher,
      loggerFactory: NamedLoggerFactory,
  )(implicit ex: ExecutionContext): TransactionTreeFactory =
    if (synchronizerId.protocolVersion >= ProtocolVersion.v35) {
      new NextGenTransactionTreeFactory(
        submittingParticipant,
        synchronizerId,
        cantonContractIdVersion,
        cryptoOps,
        hasher,
        loggerFactory,
      )
    } else {
      new LegacyTransactionTreeFactory(
        submittingParticipant,
        synchronizerId,
        cantonContractIdVersion,
        cryptoOps,
        hasher,
        loggerFactory,
      )
    }

  def contractInstanceLookup(
      contractStore: ContractLookup
  )(implicit ex: ExecutionContext, traceContext: TraceContext): ContractInstanceOfId = { id =>
    contractStore
      .lookup(id)
      .collect { case c: GenContractInstance => c: GenContractInstance }
      .toRight(ContractLookupError(id, "Unknown contract"))
  }

  /** Supertype for all errors than may arise during the conversion. */
  sealed trait TransactionTreeConversionError extends Product with Serializable with PrettyPrinting

  /** Indicates that a contract instance could not be looked up by an instance of
    * [[ContractInstanceOfId]].
    */
  final case class ContractLookupError(id: LfContractId, message: String)
      extends TransactionTreeConversionError {
    override protected def pretty: Pretty[ContractLookupError] = prettyOfClass(
      param("id", _.id),
      param("message", _.message.unquoted),
    )
  }

  final case class SubmitterMetadataError(message: String) extends TransactionTreeConversionError {
    override protected def pretty: Pretty[SubmitterMetadataError] = prettyOfClass(
      unnamedParam(_.message.unquoted)
    )
  }

  final case class RolledBackEffect(context: RollbackContext, viewPosition: ViewPosition)
      extends TransactionTreeConversionError {
    override protected def pretty: Pretty[RolledBackEffect] = prettyOfClass(
      param("context", _.context),
      param("view position", _.viewPosition),
    )
  }

  // TODO(i3013) Remove this error
  final case class ViewParticipantDataError(message: String)
      extends TransactionTreeConversionError {
    override protected def pretty: Pretty[ViewParticipantDataError] = prettyOfClass(
      unnamedParam(_.message.unquoted)
    )
  }

  final case class MissingContractKeyLookupError(key: LfGlobalKey)
      extends TransactionTreeConversionError {
    override protected def pretty: Pretty[MissingContractKeyLookupError] =
      prettyOfClass(unnamedParam(_.key))
  }

  final case class ContractKeyResolutionError(error: LegacyTransactionErrors.KeyInputError)
      extends TransactionTreeConversionError {
    override protected def pretty: Pretty[ContractKeyResolutionError] = prettyOfClass(
      unnamedParam(_.error)
    )
  }

  final case class FailedToHashContact(error: String) extends TransactionTreeConversionError {
    override protected def pretty: Pretty[FailedToHashContact] = prettyOfString(_.error)
  }

  /** Indicates that too few salts have been supplied for creating a view */
  case object TooFewSalts extends TransactionTreeConversionError {
    override protected def pretty: Pretty[TooFewSalts.type] = prettyOfObject[TooFewSalts.type]
  }
  type TooFewSalts = TooFewSalts.type

  final case class UnknownPackageError(unknownTo: Seq[PackageUnknownTo])
      extends TransactionTreeConversionError {
    override protected def pretty: Pretty[UnknownPackageError] =
      prettyOfString(err => show"Some packages are not known to all informees.\n${err.unknownTo}")
  }

  final case class ConflictingPackagePreferenceError(
      conflicts: Map[LfPackageName, Set[LfPackageId]]
  ) extends TransactionTreeConversionError {
    override protected def pretty: Pretty[ConflictingPackagePreferenceError] = prettyOfString {
      err =>
        show"Detected conflicting package-ids for the same package name\n${err.conflicts}"
    }
  }

  final case class ContractIdAbsolutizationError(message: String)
      extends TransactionTreeConversionError {
    override protected def pretty: Pretty[ContractIdAbsolutizationError] = prettyOfClass(
      unnamedParam(_.message.unquoted)
    )
  }

  final case class PackageUnknownTo(
      packageId: LfPackageId,
      participantId: ParticipantId,
  ) extends PrettyPrinting {
    override protected def pretty: Pretty[PackageUnknownTo] = prettyOfString { put =>
      show"Participant $participantId has not vetted ${put.packageId}"
    }
  }

}
