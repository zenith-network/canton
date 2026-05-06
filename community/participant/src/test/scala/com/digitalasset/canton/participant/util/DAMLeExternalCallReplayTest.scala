// Copyright (c) 2026 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.participant.util

import com.digitalasset.canton.BaseTest
import com.digitalasset.daml.lf.data.Bytes as LfBytes
import com.digitalasset.daml.lf.transaction.SerializationVersion
import org.scalatest.wordspec.AnyWordSpec

class DAMLeExternalCallReplayTest extends AnyWordSpec with BaseTest {

  private val stored =
    (
      LfBytes.assertFromString("0a0b"),
      LfBytes.assertFromString("0102"),
      LfBytes.assertFromString("deadbeef"),
      SerializationVersion.V2,
    )

  "externalCallEvidenceMismatch" should {
    "accept matching observer replay evidence" in {
      DAMLe.externalCallEvidenceMismatch(
        stored,
        requestedConfigHash = "0a0b",
        requestedInput = "0102",
        requestedValueSerializationVersion = SerializationVersion.V2,
        computedOutput = None,
      ) shouldBe None
    }

    "accept matching confirmer replay evidence" in {
      DAMLe.externalCallEvidenceMismatch(
        stored,
        requestedConfigHash = "0a0b",
        requestedInput = "0102",
        requestedValueSerializationVersion = SerializationVersion.V2,
        computedOutput = Some("deadbeef"),
      ) shouldBe None
    }

    "report config, input, version, and confirmer output mismatches" in {
      val mismatch = DAMLe
        .externalCallEvidenceMismatch(
          stored,
          requestedConfigHash = "0a0c",
          requestedInput = "0103",
          requestedValueSerializationVersion = SerializationVersion.V1,
          computedOutput = Some("feedface"),
        )
        .value

      mismatch should include("config expected '0a0b' but engine requested '0a0c'")
      mismatch should include("input expected '0102' but engine requested '0103'")
      mismatch should include(
        "value serialization version expected 'V2' but engine requested 'V1'"
      )
      mismatch should include("output expected 'deadbeef' but handler computed 'feedface'")
    }
  }
}
