// Copyright (c) 2026 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.participant.protocol.validation

import cats.syntax.either.*
import com.digitalasset.canton.participant.protocol.validation.InternalConsistencyChecker.{
  ErrorWithInternalConsistencyCheck,
  InconsistentContractKeyError,
}
import com.digitalasset.canton.LfPartyId
import com.digitalasset.canton.data.{FullTransactionViewTree, TransactionView, ViewParticipantData}
import com.digitalasset.canton.protocol.{
  ExampleContractFactory,
  ExampleTransactionFactory,
  LfNodeId,
}
import com.digitalasset.canton.topology.ParticipantId
import com.digitalasset.canton.version.ProtocolVersion
import com.digitalasset.daml.lf.data.{Bytes, ImmArray}
import com.digitalasset.daml.lf.transaction.{ExternalCallResult, NodeId}
import com.digitalasset.daml.lf.transaction.test.TreeTransactionBuilder.NodeOps
import com.digitalasset.daml.lf.transaction.test.{
  TestIdFactory,
  TestNodeBuilder,
  TransactionBuilder,
  TreeTransactionBuilder,
}
import com.digitalasset.daml.lf.value.Value
import com.digitalasset.daml.lf.value.Value.ValueRecord

import TransactionBuilder.Implicits.*

class NextGenInternalConsistencyCheckerTest extends InternalConsistencyCheckerTest {

  private val externalCallResult = ExternalCallResult(
    extensionId = "extension",
    functionId = "function",
    config = Bytes.fromStringUtf8("config"),
    input = Bytes.fromStringUtf8("input"),
    output = Bytes.fromStringUtf8("output"),
  )

  private val otherExternalCallOutput =
    externalCallResult.copy(output = Bytes.fromStringUtf8("other-output"))

  "Internal consistency checker" when {

    val participantId: ParticipantId = ParticipantId("test")
    val sut = new NextGenInternalConsistencyChecker(participantId, loggerFactory)

    "rollback scope order" should checkRollbackScopeOrder()

    "standard happy cases" should checkStandardHappyCases(sut)

    "external call consistency cases" should checkExternalCallConsistencyCases(sut)

    "key consistency cases" should checkKeyConsistencyCases(sut)

  }

  def checkExternalCallConsistencyCases(sut: InternalConsistencyChecker): Unit =
    "external call consistency" must {
      "not reject conflicting outputs as an internal consistency failure" in {
        val devFactory =
          new ExampleTransactionFactory(versionOverride = Some(ProtocolVersion.dev))()
        val example = devFactory.MultipleRoots
        val root0Index = 4
        val root1Index = 5
        val root0 = withExternalCallResults(
          example.rootViews(root0Index),
          ImmArray(
            externalCallViewResult(
              nodeId = LfNodeId(0),
              result = externalCallResult,
              checkingParties = Set(ExampleTransactionFactory.signatory),
            )
          ),
        )
        val root1 = withExternalCallResults(
          example.rootViews(root1Index),
          ImmArray(
            externalCallViewResult(
              nodeId = LfNodeId(1),
              result = otherExternalCallOutput,
              checkingParties = Set(ExampleTransactionFactory.signatory),
            )
          ),
        )

        def treeWithOnlyRoot(index: Int, view: TransactionView): FullTransactionViewTree =
          devFactory.rootTransactionViewTree(
            example.rootViews.zipWithIndex.map { case (rootView, rootIndex) =>
              if (rootIndex == index) view else ExampleTransactionFactory.blinded(rootView)
            }*
          )

        val root0Tree = treeWithOnlyRoot(root0Index, root0)
        val root1Tree = treeWithOnlyRoot(root1Index, root1)

        checkViews(sut, Seq(root0Tree, root1Tree)) shouldBe Either.unit
      }
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

  private def withExternalCallResults(
      view: TransactionView,
      results: ImmArray[ViewParticipantData.ViewExternalCallResult],
  ): TransactionView =
    TransactionView.Optics.viewParticipantDataUnsafe
      .modify(vpd => vpd.tryUnwrap.copy(externalCallResults = results))(view)

  def checkKeyConsistencyCases(sut: InternalConsistencyChecker): Unit = {
    val ids: Iterator[NodeId] = Iterator.from(0).map(NodeId.apply)
    val txBuilder = new TreeTransactionBuilder with TestNodeBuilder with TestIdFactory {
      override def nextNodeId(): NodeId = ids.next()
    }

    val someCreate = txBuilder.create(
      id = txBuilder.newCid,
      templateId = "M:T",
      argument = Value.ValueUnit,
      signatories = List("signatory"),
      observers = List("observer"),
    )

    val someExercise = txBuilder.exercise(
      someCreate,
      choice = "C",
      consuming = false,
      actingParties = Set("A"),
      ValueRecord(None, ImmArray.empty),
      byKey = false,
    )

    val key = ExampleContractFactory.buildKeyWithMaintainers()

    val cId1 = txBuilder.newCid
    val cId2 = txBuilder.newCid
    val cId3 = txBuilder.newCid

    s"key consistency" must {
      "allow a non-exhaustive query followed by an exhaustive one" in {
        val tx = txBuilder.toTransaction(
          someExercise.withChildren(
            txBuilder.queryByKey(key = key, Vector(cId1, cId2), exhaustive = false),
            txBuilder.queryByKey(key = key, Vector(cId1, cId2), exhaustive = true),
          )
        )
        checkTransaction(sut, Seq(tx), Set(key.globalKey)) shouldBe Either.unit
      }
      "disallow the inconsistent contract ordering" in {
        val tx = txBuilder.toTransaction(
          someExercise.withChildren(
            txBuilder.queryByKey(key = key, Vector(cId1, cId2, cId3), exhaustive = false),
            txBuilder.queryByKey(key = key, Vector(cId2, cId3), exhaustive = false),
          )
        )
        inside(checkTransaction(sut, Seq(tx), Set(key.globalKey))) {
          case Left(ErrorWithInternalConsistencyCheck(InconsistentContractKeyError(actual))) =>
            actual shouldBe key.globalKey
        }
      }
      "allow inconsistent contract ordering if the key is not hosted" in {
        val tx = txBuilder.toTransaction(
          someExercise.withChildren(
            txBuilder.queryByKey(key = key, Vector(cId1, cId2, cId3), exhaustive = false),
            txBuilder.queryByKey(key = key, Vector(cId2, cId3), exhaustive = false),
          )
        )
        checkTransaction(sut, Seq(tx), Set.empty) shouldBe Either.unit
      }
      "disallow an exhaustive query followed by one that returns additional contracts" in {
        val tx = txBuilder.toTransaction(
          someExercise.withChildren(
            txBuilder.queryByKey(key = key, Vector(cId1, cId2), exhaustive = true),
            txBuilder.queryByKey(key = key, Vector(cId1, cId2, cId3), exhaustive = false),
          )
        )
        inside(checkTransaction(sut, Seq(tx), Set(key.globalKey))) {
          case Left(ErrorWithInternalConsistencyCheck(InconsistentContractKeyError(actual))) =>
            actual shouldBe key.globalKey
        }
      }
      "disallow an exhaustive query followed by one in a subsequent transaction that returns additional contracts" in {
        val tx1 = txBuilder.toTransaction(
          someExercise.withChildren(
            txBuilder.queryByKey(key = key, Vector(cId1, cId2), exhaustive = true)
          )
        )
        val tx2 = txBuilder.toTransaction(
          someExercise.withChildren(
            txBuilder.queryByKey(key = key, Vector(cId1, cId2, cId3), exhaustive = false)
          )
        )
        inside(checkTransaction(sut, Seq(tx1, tx2), Set(key.globalKey))) {
          case Left(ErrorWithInternalConsistencyCheck(InconsistentContractKeyError(actual))) =>
            actual shouldBe key.globalKey
        }
      }
    }
  }

}
