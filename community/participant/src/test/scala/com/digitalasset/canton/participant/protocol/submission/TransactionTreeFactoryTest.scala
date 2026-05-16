// Copyright (c) 2026 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.participant.protocol.submission

import com.digitalasset.canton.BaseTest
import com.digitalasset.canton.data.TransactionView
import com.digitalasset.canton.protocol.{ExampleTransactionFactory, LfNodeId, RollbackContext}
import com.digitalasset.daml.lf.data.ImmArray
import org.scalatest.wordspec.AnyWordSpec

final class TransactionTreeFactoryTest extends AnyWordSpec with BaseTest {

  "externalCallResultsFromCoreNodes" should {
    "not inspect child views when core nodes contain no external call results" in {
      val exercise = ExampleTransactionFactory.exerciseNode(
        targetCoid = ExampleTransactionFactory.suffixedId(-1, 0),
        signatories = Set(ExampleTransactionFactory.signatory),
      )
      def throwIfInspected: TransactionView =
        throw new AssertionError("child views should not be inspected")
      val throwingChildViews = LazyList.continually(throwIfInspected)

      TransactionTreeFactory.externalCallResultsFromCoreNodes(
        coreOtherNodes = List((LfNodeId(0), exercise, RollbackContext.empty.rollbackScope)),
        childViews = throwingChildViews,
        normalizeNodeId = identity,
        originalRootNodeIds = Set.empty,
        submittingAdminPartyO = None,
      ) shouldBe ImmArray.Empty
    }
  }
}
