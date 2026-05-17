// Copyright (c) 2026 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.participant.protocol.validation

import com.digitalasset.canton.data.{ViewParticipantData, ViewPosition}
import com.digitalasset.canton.discard.Implicits.DiscardOps
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.protocol.LfNodeId
import com.digitalasset.canton.LfPartyId
import com.digitalasset.daml.lf.data.Bytes

import scala.collection.mutable

object ExternalCallConsistencyChecker {

  final case class ExternalCallKey(
      extensionId: String,
      functionId: String,
      config: Bytes,
      input: Bytes,
  ) extends PrettyPrinting {
    override protected def pretty: Pretty[ExternalCallKey] = prettyOfClass(
      param("extensionId", _.extensionId.unquoted),
      param("functionId", _.functionId.unquoted),
      param("configBytes", key => bytesSize(key.config)),
      param("inputBytes", key => bytesSize(key.input)),
    )
  }

  private object ExternalCallKey {
    def from(result: ViewParticipantData.ViewExternalCallResult): ExternalCallKey = {
      val call = result.result
      ExternalCallKey(call.extensionId, call.functionId, call.config, call.input)
    }
  }

  final case class ExternalCallOccurrence(
      viewPosition: ViewPosition,
      nodeId: LfNodeId,
      callIndex: Int,
  ) extends PrettyPrinting {
    def description: String =
      s"view position ${viewPosition.position.mkString("[", ", ", "]")}, " +
        s"node id ${nodeId.index}, call index $callIndex"

    override protected def pretty: Pretty[ExternalCallOccurrence] =
      prettyOfString(_.description)
  }

  final case class Inconsistency(
      key: ExternalCallKey,
      outputs: Set[Bytes],
      occurrences: Set[ExternalCallOccurrence],
  ) extends PrettyPrinting {
    def description: String =
      s"External call result disagreement for ${key.extensionId}/${key.functionId} " +
        s"(config bytes: ${bytesSize(key.config)}, input bytes: ${bytesSize(key.input)}, " +
        s"output byte sizes: ${outputs.toSeq.map(bytesSize).sorted.mkString("[", ", ", "]")}, " +
        s"occurrences: ${occurrences.toSeq
            .sortBy(occurrence =>
              (occurrence.viewPosition.position.toString, occurrence.nodeId.index, occurrence.callIndex)
            )
            .map(_.description)
            .mkString("[", "; ", "]")})"

    override protected def pretty: Pretty[Inconsistency] =
      prettyOfClass(param("details", _.description.unquoted))
  }

  final case class Result(inconsistencies: Map[LfPartyId, Inconsistency]) extends PrettyPrinting {
    def inconsistentParties: Set[LfPartyId] = inconsistencies.keySet

    override protected def pretty: Pretty[Result] = prettyOfClass(
      param("inconsistencies", _.inconsistencies)
    )
  }

  object Result {
    val empty: Result = Result(Map.empty)
  }

  private def bytesSize(bytes: Bytes): Int = bytes.toByteString.size()
}

final class ExternalCallConsistencyChecker {
  import ExternalCallConsistencyChecker.*

  def check(
      viewValidationResults: Map[ViewPosition, ViewValidationResult],
      hostedConfirmingParties: Set[LfPartyId],
  ): Result =
    if (hostedConfirmingParties.isEmpty) Result.empty
    else {
      val outputsByPartyAndKey =
        mutable.LinkedHashMap.empty[
          LfPartyId,
          mutable.Map[ExternalCallKey, mutable.Map[Bytes, mutable.Set[ExternalCallOccurrence]]],
        ]

      val orderedViewValidationResults =
        viewValidationResults.toSeq.sortBy(_._1)(ViewPosition.orderViewPosition.toOrdering)

      orderedViewValidationResults.foreach { case (viewPosition, viewValidationResult) =>
        val viewParticipantData = viewValidationResult.view.viewParticipantData
        if (viewParticipantData.representativeProtocolVersion.representative.isDev) {
          viewParticipantData.externalCallResults.foreach { externalCallResult =>
            val affectedHostedParties =
              externalCallResult.checkingParties.intersect(hostedConfirmingParties)
            if (affectedHostedParties.nonEmpty) {
              val key = ExternalCallKey.from(externalCallResult)
              val output = externalCallResult.result.output
              val occurrence = ExternalCallOccurrence(
                viewPosition,
                externalCallResult.nodeId,
                externalCallResult.callIndex,
              )

              affectedHostedParties.foreach { party =>
                val outputsByKey =
                  outputsByPartyAndKey.getOrElseUpdate(party, mutable.LinkedHashMap.empty)
                val occurrencesByOutput =
                  outputsByKey.getOrElseUpdate(key, mutable.LinkedHashMap.empty)
                val occurrences =
                  occurrencesByOutput.getOrElseUpdate(output, mutable.Set.empty)
                occurrences.add(occurrence).discard
              }
            }
          }
        }
      }

      val inconsistencies = outputsByPartyAndKey.iterator.flatMap {
        case (party, outputsByKey) =>
          outputsByKey.iterator.collectFirst {
            case (key, occurrencesByOutput) if occurrencesByOutput.sizeCompare(1) > 0 =>
              party -> Inconsistency(
                key,
                occurrencesByOutput.keySet.toSet,
                occurrencesByOutput.valuesIterator.flatMap(_.iterator).toSet,
              )
          }
      }.toMap

      Result(inconsistencies)
    }
}
