// Copyright (c) 2026 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.participant.protocol.validation

import com.digitalasset.canton.data.{
  ParticipantTransactionView,
  TransactionView,
  ViewParticipantData,
  ViewPosition,
}
import com.digitalasset.canton.protocol.*
import com.digitalasset.canton.version.ProtocolVersion
import com.digitalasset.canton.{BaseTest, LfPartyId}
import com.digitalasset.daml.lf.data.{Bytes, ImmArray}
import com.digitalasset.daml.lf.transaction.ExternalCallResult
import org.scalatest.wordspec.AnyWordSpec

import scala.concurrent.ExecutionContext

class ExternalCallConsistencyCheckerTest extends AnyWordSpec with BaseTest {

  implicit val ec: ExecutionContext = directExecutionContext

  private val sut = new ExternalCallConsistencyChecker
  private val factory =
    new ExampleTransactionFactory(versionOverride = Some(ProtocolVersion.dev))()

  private val partyA = ExampleTransactionFactory.signatory
  private val partyB = ExampleTransactionFactory.submitter
  private val partyC = ExampleTransactionFactory.observer

  private val externalCallResult = ExternalCallResult(
    extensionId = "extension",
    functionId = "function",
    config = Bytes.fromStringUtf8("config"),
    input = Bytes.fromStringUtf8("input"),
    output = Bytes.fromStringUtf8("output"),
  )

  private val otherExternalCallOutput =
    externalCallResult.copy(output = Bytes.fromStringUtf8("other-output"))

  private def externalCallViewResult(
      nodeId: LfNodeId,
      result: ExternalCallResult,
      checkingParties: Set[LfPartyId],
      callIndex: Int = 0,
  ): ViewParticipantData.ViewExternalCallResult =
    ViewParticipantData.ViewExternalCallResult(
      result = result,
      nodeId = nodeId,
      callIndex = callIndex,
      checkingParties = checkingParties,
    )

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

  private def check(
      leftCheckingParties: Set[LfPartyId],
      rightCheckingParties: Set[LfPartyId],
      hostedParties: Set[LfPartyId],
      rightResult: ExternalCallResult = otherExternalCallOutput,
  ): ExternalCallConsistencyChecker.Result = {
    val example = factory.MultipleRoots
    val left = withExternalCallResults(
      example.rootViews(4),
      ImmArray(
        externalCallViewResult(
          nodeId = LfNodeId(0),
          result = externalCallResult,
          checkingParties = leftCheckingParties,
        )
      ),
    )
    val right = withExternalCallResults(
      example.rootViews(5),
      ImmArray(
        externalCallViewResult(
          nodeId = LfNodeId(1),
          result = rightResult,
          checkingParties = rightCheckingParties,
        )
      ),
    )

    sut.check(
      Map(
        ViewPosition.root -> validationResult(left),
        ViewPosition(
          List(ViewPosition.MerkleSeqIndex(List(ViewPosition.MerkleSeqIndex.Direction.Right)))
        ) ->
          validationResult(right),
      ),
      hostedParties,
    )
  }

  "ExternalCallConsistencyChecker" should {
    "report only hosted parties that check conflicting outputs" in {
      val result = check(
        leftCheckingParties = Set(partyA),
        rightCheckingParties = Set(partyA),
        hostedParties = Set(partyA, partyB),
      )

      result.inconsistentParties shouldBe Set(partyA)
    }

    "not report conflicting outputs for disjoint checking parties" in {
      val result = check(
        leftCheckingParties = Set(partyA),
        rightCheckingParties = Set(partyB),
        hostedParties = Set(partyA, partyB),
      )

      result.inconsistentParties shouldBe Set.empty
    }

    "not report non-hosted checking parties" in {
      val result = check(
        leftCheckingParties = Set(partyC),
        rightCheckingParties = Set(partyC),
        hostedParties = Set(partyA, partyB),
      )

      result.inconsistentParties shouldBe Set.empty
    }

    "not report identical outputs" in {
      val result = check(
        leftCheckingParties = Set(partyA),
        rightCheckingParties = Set(partyA),
        hostedParties = Set(partyA),
        rightResult = externalCallResult,
      )

      result.inconsistentParties shouldBe Set.empty
    }

    "not report different semantic calls with different outputs" in {
      val result = check(
        leftCheckingParties = Set(partyA),
        rightCheckingParties = Set(partyA),
        hostedParties = Set(partyA),
        rightResult = otherExternalCallOutput.copy(functionId = "other-function"),
      )

      result.inconsistentParties shouldBe Set.empty
    }

    "report repeated semantic calls on the same node with different outputs" in {
      val example = factory.MultipleRoots
      val view = withExternalCallResults(
        example.rootViews(4),
        ImmArray(
          externalCallViewResult(
            nodeId = LfNodeId(0),
            result = externalCallResult,
            checkingParties = Set(partyA),
            callIndex = 0,
          ),
          externalCallViewResult(
            nodeId = LfNodeId(0),
            result = otherExternalCallOutput,
            checkingParties = Set(partyA),
            callIndex = 1,
          ),
        ),
      )

      val result = sut.check(
        Map(ViewPosition.root -> validationResult(view)),
        Set(partyA),
      )

      result.inconsistentParties shouldBe Set(partyA)
      val inconsistency = result.inconsistencies(partyA)
      inconsistency.outputs shouldBe Set(externalCallResult.output, otherExternalCallOutput.output)
      inconsistency.occurrences.map(occurrence =>
        occurrence.nodeId -> occurrence.callIndex
      ) shouldBe Set(LfNodeId(0) -> 0, LfNodeId(0) -> 1)
    }
  }
}
