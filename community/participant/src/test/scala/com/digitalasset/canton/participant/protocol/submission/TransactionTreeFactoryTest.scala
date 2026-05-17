// Copyright (c) 2026 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.participant.protocol.submission

import com.digitalasset.canton.BaseTest
import com.digitalasset.canton.protocol.{ExampleTransactionFactory, LfNodeId, RollbackContext}
import com.digitalasset.daml.lf.data.{Bytes, ImmArray}
import com.digitalasset.daml.lf.transaction.ExternalCallResult
import org.scalatest.wordspec.AnyWordSpec

final class TransactionTreeFactoryTest extends AnyWordSpec with BaseTest {

  private val externalCallResult = ExternalCallResult(
    extensionId = "extension",
    functionId = "function",
    config = Bytes.fromStringUtf8("config"),
    input = Bytes.fromStringUtf8("input"),
    output = Bytes.fromStringUtf8("output"),
  )

  "externalCallResultsFromCoreNodes" should {
    "return empty results when core nodes contain no external call results" in {
      val exercise = ExampleTransactionFactory.exerciseNode(
        targetCoid = ExampleTransactionFactory.suffixedId(-1, 0),
        signatories = Set(ExampleTransactionFactory.signatory),
      )

      TransactionTreeFactory.externalCallResultsFromCoreNodes(
        coreOtherNodes = List((LfNodeId(0), exercise, RollbackContext.empty.rollbackScope)),
        normalizeNodeIds = _ => fail("node id normalization should not be requested"),
      ) shouldBe ImmArray.Empty
    }

    "request normalization once for distinct external call node ids" in {
      val exercise = ExampleTransactionFactory
        .exerciseNode(
          targetCoid = ExampleTransactionFactory.suffixedId(-1, 0),
          signatories = Set(ExampleTransactionFactory.signatory),
        )
        .copy(
          externalCallResults = ImmArray(
            externalCallResult,
            externalCallResult.copy(functionId = "other-function"),
          )
        )
      var normalizationRequests = List.empty[Set[LfNodeId]]

      val results = TransactionTreeFactory.externalCallResultsFromCoreNodes(
        coreOtherNodes = List((LfNodeId(3), exercise, RollbackContext.empty.rollbackScope)),
        normalizeNodeIds = nodeIds => {
          normalizationRequests = normalizationRequests :+ nodeIds
          nodeIds.map(_ -> LfNodeId(1)).toMap
        },
      )

      normalizationRequests shouldBe List(Set(LfNodeId(3)))
      results.toSeq.map(_.nodeId) shouldBe Seq(LfNodeId(1), LfNodeId(1))
    }

    "use only exercise signatories as checking parties" in {
      val exercise = ExampleTransactionFactory
        .exerciseNode(
          targetCoid = ExampleTransactionFactory.suffixedId(-1, 0),
          signatories = Set(ExampleTransactionFactory.signatory),
          actingParties = Set(ExampleTransactionFactory.submitter),
        )
        .copy(externalCallResults = ImmArray(externalCallResult))

      val results = TransactionTreeFactory.externalCallResultsFromCoreNodes(
        coreOtherNodes = List((LfNodeId(3), exercise, RollbackContext.empty.rollbackScope)),
        normalizeNodeIds = nodeIds => nodeIds.map(_ -> LfNodeId(1)).toMap,
      )

      results.toSeq.loneElement.checkingParties shouldBe Set(ExampleTransactionFactory.signatory)
    }
  }
}
