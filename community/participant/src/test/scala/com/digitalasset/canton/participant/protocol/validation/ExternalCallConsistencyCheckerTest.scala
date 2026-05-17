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

  private final class NoBulkToSeqMap(entries: Seq[(ViewPosition, ViewValidationResult)])
      extends scala.collection.immutable.AbstractMap[ViewPosition, ViewValidationResult] {
    override def get(key: ViewPosition): Option[ViewValidationResult] =
      entries.collectFirst { case (`key`, result) => result }

    override def removed(key: ViewPosition): Map[ViewPosition, ViewValidationResult] =
      entries.toMap.removed(key)

    override def updated[V1 >: ViewValidationResult](
        key: ViewPosition,
        value: V1,
    ): Map[ViewPosition, V1] =
      entries.toMap.updated(key, value)

    override def iterator: Iterator[(ViewPosition, ViewValidationResult)] = entries.iterator

    override def toSeq: Seq[(ViewPosition, ViewValidationResult)] =
      fail("checker should not materialize all view results before filtering external calls")
  }

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

    "not bulk materialize views when no external call results are present" in {
      val example = factory.MultipleRoots
      val viewResults = new NoBulkToSeqMap(
        Seq(
          ViewPosition.root -> validationResult(example.rootViews(4)),
          ViewPosition(
            List(ViewPosition.MerkleSeqIndex(List(ViewPosition.MerkleSeqIndex.Direction.Right)))
          ) -> validationResult(example.rootViews(5)),
        )
      )

      sut.check(viewResults, hostedConfirmingParties = Set(partyA)) shouldBe
        ExternalCallConsistencyChecker.Result.empty
    }

    "bound disagreement diagnostic details" in {
      val longExtensionId = Iterator.fill(300)("e").mkString
      val longFunctionId = Iterator.fill(300)("f").mkString
      val example = factory.MultipleRoots
      val externalCallResults = ImmArray.from((0 until 20).map { index =>
        externalCallViewResult(
          nodeId = LfNodeId(index),
          result = externalCallResult.copy(
            extensionId = longExtensionId,
            functionId = longFunctionId,
            output = Bytes.fromStringUtf8("x" * index),
          ),
          checkingParties = Set(partyA),
          callIndex = index,
        )
      })
      val view = withExternalCallResults(example.rootViews(4), externalCallResults)

      val result = sut.check(Map(ViewPosition.root -> validationResult(view)), Set(partyA))
      val description = result.inconsistencies(partyA).description

      description should not include longExtensionId
      description should not include longFunctionId
      description should include("chars omitted")
      description should include("more output sizes")
      description should include("more occurrences")
      description.length should be < 1200
    }
  }
}
