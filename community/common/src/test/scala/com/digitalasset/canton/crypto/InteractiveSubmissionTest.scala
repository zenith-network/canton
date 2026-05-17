// Copyright (c) 2026 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.crypto

import com.digitalasset.canton.BaseTest
import com.digitalasset.canton.data.LedgerTimeBoundaries
import com.digitalasset.canton.protocol.LfSerializationVersion
import com.digitalasset.canton.protocol.hash.HashTracer
import com.digitalasset.canton.topology.{SynchronizerId, UniqueIdentifier}
import com.digitalasset.canton.version.{HashingSchemeVersion, ProtocolVersion}
import com.digitalasset.daml.lf.data.{ImmArray, Ref, Time}
import com.digitalasset.daml.lf.transaction.VersionedTransaction
import org.scalatest.wordspec.AnyWordSpec

import java.util.UUID

class InteractiveSubmissionTest extends AnyWordSpec with BaseTest {

  private val metadata = InteractiveSubmission.TransactionMetadataForHashing.create(
    actAs = Set(Ref.Party.assertFromString("alice")),
    commandId = Ref.CommandId.assertFromString("command-id"),
    transactionUUID = UUID.fromString("4c6471d3-4e09-49dd-addf-6cd90e19c583"),
    mediatorGroup = 0,
    synchronizer = SynchronizerId(UniqueIdentifier.tryCreate("synchronizer", "id")),
    timeBoundaries = LedgerTimeBoundaries(
      Time.Range(
        Time.Timestamp.assertFromLong(0xaaaa),
        Time.Timestamp.assertFromLong(0xbbbb),
      )
    ),
    preparationTime = Time.Timestamp.Epoch,
    maxRecordTime = Some(Time.Timestamp.assertFromLong(0xcccc)),
    disclosedContracts = Map.empty,
  )

  "InteractiveSubmission.computeVersionedHash" should {
    "reject LF serialization versions unsupported by the protocol version" in {
      val transaction =
        VersionedTransaction(LfSerializationVersion.VDev, nodes = Map.empty, roots = ImmArray.empty)

      val result = InteractiveSubmission.computeVersionedHash(
        HashingSchemeVersion.V3,
        transaction,
        metadata,
        nodeSeeds = Map.empty,
        ProtocolVersion.v35,
        HashTracer.NoOp,
      )

      inside(result.left.value) {
        case InteractiveSubmission.UnsupportedLfSerializationVersion(
              LfSerializationVersion.VDev,
              ProtocolVersion.v35,
              ProtocolVersion.dev,
            ) =>
      }
    }
  }
}
