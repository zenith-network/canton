// Copyright (c) 2026 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.participant.protocol.validation

import com.digitalasset.canton.topology.ParticipantId

class LegacyInternalConsistencyCheckerTest extends InternalConsistencyCheckerTest {

  "Internal consistency checker" when {

    val participantId: ParticipantId = ParticipantId("test")
    val sut = new LegacyInternalConsistencyChecker(participantId, loggerFactory)

    "checker implementation" should {
      "use the legacy implementation" in {
        sut shouldBe a[LegacyInternalConsistencyChecker]
      }
    }

    "rollback scope order" should checkRollbackScopeOrder()

    "standard happy cases" should checkStandardHappyCases(sut)

    "external call consistency cases" should checkExternalCallConsistencyCases(sut)

  }
}
