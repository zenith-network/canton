// Copyright (c) 2026 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.participant.protocol.submission

import com.digitalasset.canton.BaseTest
import com.digitalasset.canton.protocol.{ExampleTransactionFactory, LfNodeId, RollbackContext}
import com.digitalasset.daml.lf.data.ImmArray
import org.scalatest.wordspec.AnyWordSpec

final class TransactionTreeFactoryTest extends AnyWordSpec with BaseTest {

  "externalCallResultsFromCoreNodes" should {
    "return empty results when core nodes contain no external call results" in {
      val exercise = ExampleTransactionFactory.exerciseNode(
        targetCoid = ExampleTransactionFactory.suffixedId(-1, 0),
        signatories = Set(ExampleTransactionFactory.signatory),
      )

      TransactionTreeFactory.externalCallResultsFromCoreNodes(
        coreOtherNodes = List((LfNodeId(0), exercise, RollbackContext.empty.rollbackScope)),
        normalizeNodeId = identity,
        originalRootNodeIds = Set.empty,
        submittingAdminPartyO = None,
      ) shouldBe ImmArray.Empty
    }
  }
}
