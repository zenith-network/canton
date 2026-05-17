// Copyright (c) 2026 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.participant.protocol.validation

import cats.data.EitherT
import com.digitalasset.canton.config.RequireTypes.NonNegativeInt
import com.digitalasset.canton.data.{
  CantonTimestamp,
  ParticipantTransactionView,
  TransactionView,
  ViewConfirmationParameters,
  ViewParticipantData,
  ViewPosition,
}
import com.digitalasset.canton.lifecycle.FutureUnlessShutdown
import com.digitalasset.canton.logging.SuppressionRule
import com.digitalasset.canton.participant.protocol.LedgerEffectAbsolutizer.ViewAbsoluteLedgerEffect
import com.digitalasset.canton.participant.protocol.conflictdetection.ConflictDetectionHelpers
import com.digitalasset.canton.protocol.*
import com.digitalasset.canton.protocol.ExampleTransactionFactory.{
  signatory,
  submittingParticipant,
  submitter,
}
import com.digitalasset.canton.protocol.messages.{ConfirmationResponse, LocalApprove, LocalReject}
import com.digitalasset.canton.topology.client.TopologySnapshot
import com.digitalasset.canton.topology.ParticipantId
import com.digitalasset.canton.version.ProtocolVersion
import com.digitalasset.canton.{BaseTestWordSpec, HasExecutionContext, LfPartyId}
import com.digitalasset.daml.lf.data.{Bytes, ImmArray}
import com.digitalasset.daml.lf.transaction.ExternalCallResult
import org.slf4j.event.Level.INFO

final class TransactionConfirmationResponsesFactoryTest
    extends BaseTestWordSpec
    with HasExecutionContext {

  private val requestId = RequestId(CantonTimestamp.Epoch)
  private val factory =
    new ExampleTransactionFactory(versionOverride = Some(ProtocolVersion.dev))()
  private val sut =
    responseFactory(ProtocolVersion.dev)

  private def responseFactory(
      protocolVersion: ProtocolVersion,
      externalCallConsistencyChecker: ExternalCallConsistencyChecking =
        new ExternalCallConsistencyChecker(),
  ): TransactionConfirmationResponsesFactory =
    new TransactionConfirmationResponsesFactory(
      submittingParticipant,
      factory.psid.copy(protocolVersion = protocolVersion),
      loggerFactory,
      externalCallConsistencyChecker,
    )

  private val leftViewPosition = ViewPosition.root
  private val rightViewPosition =
    ViewPosition(
      List(ViewPosition.MerkleSeqIndex(List(ViewPosition.MerkleSeqIndex.Direction.Right)))
    )

  private val externalCallResult = ExternalCallResult(
    extensionId = "extension",
    functionId = "function",
    config = Bytes.fromStringUtf8("config"),
    input = Bytes.fromStringUtf8("input"),
    output = Bytes.fromStringUtf8("output"),
  )

  private val otherExternalCallOutput =
    externalCallResult.copy(output = Bytes.fromStringUtf8("other-output"))

  private val topologySnapshot = {
    val snapshot = mock[TopologySnapshot]
    when(snapshot.canConfirm(any[ParticipantId], any[Set[LfPartyId]])(anyTraceContext))
      .thenAnswer { (_: ParticipantId, parties: Set[LfPartyId]) =>
        FutureUnlessShutdown.pure(parties)
      }
    snapshot
  }

  private def externalCallViewResult(
      nodeId: LfNodeId,
      result: ExternalCallResult,
      checkingParties: Set[LfPartyId],
  ): ViewParticipantData.ViewExternalCallResult =
    ViewParticipantData.ViewExternalCallResult(
      result = result,
      nodeId = nodeId,
      callIndex = 0,
      checkingParties = checkingParties,
    )

  private def withConfirmers(view: TransactionView, confirmers: Set[LfPartyId]): TransactionView = {
    val confirmationParameters = ViewConfirmationParameters.create(
      informees = confirmers.map(_ -> NonNegativeInt.one).toMap,
      threshold = NonNegativeInt.tryCreate(confirmers.size),
    )
    TransactionView.Optics.viewCommonDataUnsafe
      .modify(commonData =>
        commonData.tryUnwrap.copy(viewConfirmationParameters = confirmationParameters)
      )(view)
  }

  private def withExternalCallResults(
      view: TransactionView,
      results: ImmArray[ViewParticipantData.ViewExternalCallResult],
  ): TransactionView =
    TransactionView.Optics.viewParticipantDataUnsafe
      .modify(vpd => vpd.tryUnwrap.copy(externalCallResults = results))(view)

  private def validationResult(view: TransactionView): ViewValidationResult =
    ViewValidationResult(
      ParticipantTransactionView.tryCreate(view),
      ViewActivenessResult(
        inactiveContracts = Set.empty,
        alreadyLockedContracts = Set.empty,
        existingContracts = Set.empty,
      ),
    )

  private def transactionValidationResult(
      viewValidationResults: Map[ViewPosition, ViewValidationResult],
      authorizationResult: Map[ViewPosition, String] = Map.empty,
  ): TransactionValidationResult = {
    val example = factory.MultipleRoots
    val modelConformanceResult = ModelConformanceChecker.Result(
      example.updateId,
      WellFormedTransaction.checkOrThrow(
        example.versionedSuffixedTransaction,
        example.metadata,
        WellFormedTransaction.WithSuffixesAndMerged,
      ),
      unmergedTransactionsWithoutToplevelRollbackNodes = Seq.empty,
    )

    TransactionValidationResult(
      updateId = example.updateId,
      submitterMetadataO = None,
      workflowIdO = None,
      contractConsistencyResultE = Right(()),
      authenticationResult = Map.empty,
      authorizationResult = authorizationResult,
      modelConformanceResultET = EitherT.rightT[
        FutureUnlessShutdown,
        ModelConformanceChecker.ErrorWithSubTransaction[ViewAbsoluteLedgerEffect],
      ](modelConformanceResult),
      internalConsistencyResultET = EitherT
        .rightT[FutureUnlessShutdown, InternalConsistencyChecker.ErrorWithInternalConsistencyCheck](
          ()
        ),
      consumedInputsOfHostedParties = Map.empty,
      witnessed = Map.empty,
      createdContracts = Map.empty,
      transient = Map.empty,
      activenessResult = ConflictDetectionHelpers.mkActivenessResult(),
      viewValidationResults = viewValidationResults,
      timeValidationResultE = Right(()),
      hostedWitnesses = Set.empty,
      replayCheckResult = None,
      validatedExternalTransactionHash = None,
      commitAfterFailedActivenessCheck = false,
    )
  }

  private def createResponses(
      transactionValidationResult: TransactionValidationResult,
      responseFactory: TransactionConfirmationResponsesFactory = sut,
  ): Seq[ConfirmationResponse] =
    responseFactory
      .createConfirmationResponses(
        requestId,
        malformedPayloads = Seq.empty,
        transactionValidationResult,
        topologySnapshot,
      )
      .futureValueUS
      .value
      .responses

  private def conflictingExternalCallViews: Map[ViewPosition, ViewValidationResult] = {
    val example = factory.MultipleRoots
    val confirmers = Set(submitter, signatory)
    val left = withExternalCallResults(
      withConfirmers(example.rootViews(4), confirmers),
      ImmArray(
        externalCallViewResult(
          nodeId = LfNodeId(0),
          result = externalCallResult,
          checkingParties = Set(submitter),
        )
      ),
    )
    val right = withExternalCallResults(
      withConfirmers(example.rootViews(5), confirmers),
      ImmArray(
        externalCallViewResult(
          nodeId = LfNodeId(1),
          result = otherExternalCallOutput,
          checkingParties = Set(submitter),
        )
      ),
    )

    Map(
      leftViewPosition -> validationResult(left),
      rightViewPosition -> validationResult(right),
    )
  }

  private def externalCallConsistencyCheckerReturningEmpty: ExternalCallConsistencyChecking = {
    val checker = mock[ExternalCallConsistencyChecking]
    when(
      checker.check(
        any[Map[ViewPosition, ViewValidationResult]],
        any[Set[LfPartyId]],
      )
    ).thenReturn(ExternalCallConsistencyChecker.Result.empty)
    checker
  }

  private def externalCallConsistencyCheckerReturning(
      result: ExternalCallConsistencyChecker.Result
  ): ExternalCallConsistencyChecking = {
    val checker = mock[ExternalCallConsistencyChecking]
    when(
      checker.check(
        any[Map[ViewPosition, ViewValidationResult]],
        any[Set[LfPartyId]],
      )
    ).thenReturn(result)
    checker
  }

  "TransactionConfirmationResponsesFactory" should {
    "not invoke external-call consistency checking for non-dev protocol versions" in {
      val checker = externalCallConsistencyCheckerReturningEmpty
      val stableFactory =
        new ExampleTransactionFactory(versionOverride = Some(ProtocolVersion.v35))()
      val example = stableFactory.MultipleRoots

      createResponses(
        transactionValidationResult(
          Map(
            leftViewPosition -> validationResult(
              withConfirmers(example.rootViews(4), Set(submitter))
            )
          )
        ),
        responseFactory(ProtocolVersion.v35, checker),
      )

      verify(checker, never).check(
        any[Map[ViewPosition, ViewValidationResult]],
        any[Set[LfPartyId]],
      )
    }

    "not invoke external-call consistency checking for dev transactions without external calls" in {
      val checker = externalCallConsistencyCheckerReturningEmpty
      val example = factory.MultipleRoots

      createResponses(
        transactionValidationResult(
          Map(
            leftViewPosition -> validationResult(
              withConfirmers(example.rootViews(4), Set(submitter))
            )
          )
        ),
        responseFactory(ProtocolVersion.dev, checker),
      )

      verify(checker, never).check(
        any[Map[ViewPosition, ViewValidationResult]],
        any[Set[LfPartyId]],
      )
    }

    "invoke external-call consistency checking once when dev external calls can affect responses" in {
      val checker = externalCallConsistencyCheckerReturningEmpty
      val example = factory.MultipleRoots
      val view = withExternalCallResults(
        withConfirmers(example.rootViews(4), Set(submitter)),
        ImmArray(
          externalCallViewResult(
            nodeId = LfNodeId(0),
            result = externalCallResult,
            checkingParties = Set(submitter),
          )
        ),
      )

      createResponses(
        transactionValidationResult(Map(leftViewPosition -> validationResult(view))),
        responseFactory(ProtocolVersion.dev, checker),
      )

      verify(checker, times(1)).check(
        any[Map[ViewPosition, ViewValidationResult]],
        any[Set[LfPartyId]],
      )
    }

    "not invoke external-call consistency checking when malformed verdicts win" in {
      val checker = externalCallConsistencyCheckerReturningEmpty
      val example = factory.MultipleRoots
      val view = withExternalCallResults(
        withConfirmers(example.rootViews(4), Set(submitter)),
        ImmArray(
          externalCallViewResult(
            nodeId = LfNodeId(0),
            result = externalCallResult,
            checkingParties = Set(submitter),
          )
        ),
      )

      loggerFactory.assertLogs(
        createResponses(
          transactionValidationResult(
            Map(leftViewPosition -> validationResult(view)),
            authorizationResult = Map(leftViewPosition -> "authorization failure"),
          ),
          responseFactory(ProtocolVersion.dev, checker),
        ),
        _.shouldBeCantonErrorCode(LocalRejectError.MalformedRejects.MalformedRequest),
      )

      verify(checker, never).check(
        any[Map[ViewPosition, ViewValidationResult]],
        any[Set[LfPartyId]],
      )
    }

    "split external-call disagreements from the general verdict by party" in {
      val responses =
        createResponses(transactionValidationResult(conflictingExternalCallViews))
      val leftResponses = responses.filter(_.viewPositionO.contains(leftViewPosition))

      leftResponses should have size 2
      inside(leftResponses.find(_.confirmingParties == Set(submitter)).value) {
        case ConfirmationResponse(_, reject: LocalReject, _) =>
          reject.isMalformed shouldBe false
          reject.reason.message should include("inconsistent external call results")
      }
      inside(leftResponses.find(_.confirmingParties == Set(signatory)).value) {
        case ConfirmationResponse(_, LocalApprove(), _) =>
          succeed
      }
    }

    "bound external-call disagreement rejection details" in {
      val longExtensionId = Iterator.fill(2000)("e").mkString
      val example = factory.MultipleRoots
      val view = withExternalCallResults(
        withConfirmers(example.rootViews(4), Set(submitter)),
        ImmArray(
          externalCallViewResult(
            nodeId = LfNodeId(0),
            result = externalCallResult,
            checkingParties = Set(submitter),
          )
        ),
      )
      val inconsistency = ExternalCallConsistencyChecker.Inconsistency(
        key = new ExternalCallConsistencyChecker.ExternalCallKey(
          extensionId = longExtensionId,
          functionId = "function",
          config = Bytes.fromStringUtf8("config"),
          input = Bytes.fromStringUtf8("input"),
        ),
        outputs = Set(
          Bytes.fromStringUtf8("output"),
          Bytes.fromStringUtf8("other-output"),
        ),
        occurrences = Set(
          ExternalCallConsistencyChecker.ExternalCallOccurrence(
            leftViewPosition,
            LfNodeId(0),
            callIndex = 0,
          ),
          ExternalCallConsistencyChecker.ExternalCallOccurrence(
            rightViewPosition,
            LfNodeId(1),
            callIndex = 0,
          ),
        ),
      )
      val checker = externalCallConsistencyCheckerReturning(
        ExternalCallConsistencyChecker.Result(Map(submitter -> inconsistency))
      )

      val responses = loggerFactory.assertLogs(SuppressionRule.LevelAndAbove(INFO))(
        createResponses(
          transactionValidationResult(Map(leftViewPosition -> validationResult(view))),
          responseFactory(ProtocolVersion.dev, checker),
        ),
        entry => {
          entry.message should include("inconsistent external call results")
          entry.message should not include longExtensionId
          entry.message should include("...")
          entry.message.length should be < 1200
        },
      )

      inside(responses.loneElement) {
        case ConfirmationResponse(_, reject: LocalReject, confirmingParties) =>
          confirmingParties shouldBe Set(submitter)
          reject.reason.message should include("inconsistent external call results")
          reject.reason.message should not include longExtensionId
          reject.reason.message.length should be < 1200
      }
    }

    "prefer malformed verdicts over external-call disagreement responses" in {
      val responses = loggerFactory.assertLogs(
        createResponses(
          transactionValidationResult(
            conflictingExternalCallViews,
            authorizationResult = Map(leftViewPosition -> "authorization failure"),
          )
        ),
        _.shouldBeCantonErrorCode(LocalRejectError.MalformedRejects.MalformedRequest),
      )
      val leftResponses = responses.filter(_.viewPositionO.contains(leftViewPosition))

      inside(leftResponses.loneElement) {
        case ConfirmationResponse(_, reject: LocalReject, confirmingParties) =>
          reject.isMalformed shouldBe true
          confirmingParties shouldBe Set.empty
      }
    }
  }
}
