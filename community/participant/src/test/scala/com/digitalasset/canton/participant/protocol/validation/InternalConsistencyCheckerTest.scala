// Copyright (c) 2026 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.participant.protocol.validation

import cats.syntax.either.*
import com.daml.nonempty.NonEmptyUtil
import com.digitalasset.canton.{BaseTest, LfPartyId}
import com.digitalasset.canton.data.{FullTransactionViewTree, TransactionView, ViewParticipantData}
import com.digitalasset.canton.participant.protocol.validation.InternalConsistencyChecker.ErrorWithInternalConsistencyCheck
import com.digitalasset.canton.protocol.*
import com.digitalasset.canton.version.ProtocolVersion
import com.digitalasset.daml.lf.data.{Bytes, ImmArray}
import com.digitalasset.daml.lf.transaction.ExternalCallResult
import org.scalatest.wordspec.AnyWordSpec

import scala.concurrent.ExecutionContext
import scala.util.Random

abstract class InternalConsistencyCheckerTest extends AnyWordSpec with BaseTest {

  implicit val ec: ExecutionContext = directExecutionContext
  protected val factory: ExampleTransactionFactory = new ExampleTransactionFactory()()

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

  def checkRollbackScopeOrder(): Unit =
    "checkRollbackScopeOrder should validate sequences of scopes" in {
      val ops: Seq[RollbackContext => RollbackContext] = Seq(
        _.enterRollback,
        _.enterRollback,
        _.exitRollback,
        _.enterRollback,
        _.exitRollback,
        _.exitRollback,
        _.enterRollback,
        _.exitRollback,
      )

      val (_, testScopes) = ops.foldLeft((RollbackContext.empty, Seq(RollbackContext.empty))) {
        case ((c, seq), op) =>
          val nc = op(c)
          (nc, seq :+ nc)
      }

      Random.shuffle(testScopes).sorted shouldBe testScopes
      InternalConsistencyChecker.checkRollbackScopeOrder(testScopes) shouldBe Either.unit
      InternalConsistencyChecker.checkRollbackScopeOrder(testScopes.reverse).isLeft shouldBe true

    }

  private val dummyViews =
    NonEmptyUtil.fromUnsafe(factory.standardHappyCases(1).rootTransactionViewTrees)

  private val dummyTx = LfTransaction(Map.empty, ImmArray.empty)

  def checkViews(
      sut: InternalConsistencyChecker,
      views: Seq[FullTransactionViewTree],
  ): Either[ErrorWithInternalConsistencyCheck, Unit] =
    sut.check(NonEmptyUtil.fromUnsafe(views), Seq(dummyTx), Set.empty)

  def checkTransaction(
      sut: InternalConsistencyChecker,
      lfTransactions: Seq[LfTransaction],
      hostedKeys: Set[LfGlobalKey],
  ): Either[ErrorWithInternalConsistencyCheck, Unit] =
    sut.check(dummyViews, lfTransactions, hostedKeys)

  def checkStandardHappyCases(sut: InternalConsistencyChecker): Unit = {
    val relevantExamples = factory.standardHappyCases.filter(_.rootTransactionViewTrees.nonEmpty)
    forEvery(relevantExamples) { example =>
      s"checking $example" must {

        "yield the correct result" in {
          checkViews(sut, example.rootTransactionViewTrees) shouldBe Either.unit
        }

        "reinterpret views individually" in {
          example.transactionViewTrees.foreach { viewTree =>
            checkViews(sut, Seq(viewTree)) shouldBe Either.unit
          }
        }
      }
    }
  }

  def checkExternalCallConsistencyCases(sut: InternalConsistencyChecker): Unit =
    "external call consistency" must {
      "reject conflicting outputs across received root view trees for overlapping checking parties" in {
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

        inside(checkViews(sut, Seq(root0Tree, root1Tree))) {
          case Left(ErrorWithInternalConsistencyCheck(error)) =>
            error.toString should include("External call result disagreement")
        }
      }
    }

}
