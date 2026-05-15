// Copyright (c) 2026 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.participant.protocol.submission

import cats.data.EitherT
import cats.syntax.functor.*
import com.digitalasset.canton.*
import com.digitalasset.canton.data.GenTransactionTree
import com.digitalasset.canton.lifecycle.FutureUnlessShutdown
import com.digitalasset.canton.lifecycle.FutureUnlessShutdownImpl.*
import com.digitalasset.canton.participant.DefaultParticipantStateValues
import com.digitalasset.canton.participant.protocol.submission.TransactionTreeFactory.*
import com.digitalasset.canton.protocol.*
import com.digitalasset.canton.protocol.ExampleTransactionFactory.{
  defaultTestingIdentityFactory,
  defaultTestingTopology,
}
import com.digitalasset.canton.protocol.WellFormedTransaction.WithoutSuffixes
import com.digitalasset.canton.topology.ParticipantId
import com.digitalasset.canton.topology.client.TopologySnapshot
import com.digitalasset.canton.topology.store.{
  PackageDependencyResolver,
  ResolvedPackagesAndDependencies,
}
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.TestContractHasher
import com.digitalasset.canton.version.ProtocolVersion
import com.digitalasset.daml.lf.CantonOnly
import com.digitalasset.daml.lf.data.{Bytes, ImmArray}
import com.digitalasset.daml.lf.data.Ref.{IdString, PackageId}
import com.digitalasset.daml.lf.transaction.ExternalCallResult
import com.digitalasset.daml.lf.transaction.BackwardsCompatibilityImplicits.*
import com.digitalasset.daml.lf.transaction.LegacyContractStateMachine
import org.scalatest.wordspec.AsyncWordSpec

import scala.concurrent.Future

@SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf"))
final class NextGenTransactionTreeFactoryTest
    extends AsyncWordSpec
    with BaseTest
    with HasExecutionContext
    with ProtocolVersionChecksAsyncWordSpec {

  private def successfulLookup(example: ExampleTransaction): ContractInstanceOfId = id =>
    EitherT.fromEither[FutureUnlessShutdown](
      example.inputContracts
        .get(id)
        .toRight(ContractLookupError(id, "Unable to lookup input contract from test data"))
    )

  private def failedLookup(testErrorMessage: String): ContractInstanceOfId =
    id => EitherT.leftT(ContractLookupError(id, testErrorMessage))

  private val externalCallResult = ExternalCallResult(
    extensionId = "extension",
    functionId = "function",
    config = Bytes.fromStringUtf8("config"),
    input = Bytes.fromStringUtf8("input"),
    output = Bytes.fromStringUtf8("output"),
  )

  private def withExternalCallResults(
      example: ExampleTransaction,
      nodeId: LfNodeId,
      results: ImmArray[ExternalCallResult],
  ): WellFormedTransaction[WithoutSuffixes] = {
    val transaction = example.versionedUnsuffixedTransaction
    val exercise = transaction.nodes(nodeId).asInstanceOf[LfNodeExercises]
    val updatedExercise = exercise.copy(
      externalCallResults = results,
      version = LfSerializationVersion.VDev,
    )
    val updatedTransaction = CantonOnly.lfVersionedTransaction(
      nodes = transaction.nodes.updated(nodeId, updatedExercise),
      roots = transaction.roots,
    )
    WellFormedTransaction.checkOrThrow(
      updatedTransaction,
      example.metadata,
      WithoutSuffixes,
    )
  }

  forAll(Table("contract id version", CantonContractIdVersion.all*)) { contractIdVersion =>
    val factory: ExampleTransactionFactory = new ExampleTransactionFactory(
      versionOverride = Some(testedProtocolVersion)
    )(cantonContractIdVersion = contractIdVersion)

    s"TransactionTreeFactoryImpl for contract ID version $contractIdVersion" should {

      def createTransactionTreeFactory(
          exampleFactory: ExampleTransactionFactory = factory,
          participantId: ParticipantId = ExampleTransactionFactory.submittingParticipant,
      ): TransactionTreeFactory =
        new NextGenTransactionTreeFactory(
          participantId,
          exampleFactory.psid,
          exampleFactory.cantonContractIdVersion,
          exampleFactory.cryptoOps,
          TestContractHasher.Async,
          loggerFactory,
        )

      def createTransactionTree(
          treeFactory: TransactionTreeFactory,
          transaction: WellFormedTransaction[WithoutSuffixes],
          contractInstanceOfId: ContractInstanceOfId,
          keyResolver: LegacyContractStateMachine.KeyResolver,
          actAs: List[LfPartyId] = List(ExampleTransactionFactory.submitter),
          snapshot: TopologySnapshot = factory.topologySnapshot,
          exampleFactory: ExampleTransactionFactory = factory,
      ): EitherT[Future, TransactionTreeConversionError, GenTransactionTree] = {
        val submitterInfo = DefaultParticipantStateValues.submitterInfo(actAs)
        treeFactory
          .createTransactionTree(
            transaction = transaction,
            submitterInfo = submitterInfo,
            workflowId = Some(WorkflowId.assertFromString("testWorkflowId")),
            mediator = exampleFactory.mediatorGroup,
            transactionSeed = exampleFactory.transactionSeed,
            transactionUuid = exampleFactory.transactionUuid,
            topologySnapshot = snapshot,
            contractOfId = contractInstanceOfId,
            legacyKeyResolver = keyResolver.fmap(_.toList.toVector),
            maxSequencingTime = exampleFactory.ledgerTime.plusSeconds(100),
            validatePackageVettings = true,
          )
          .failOnShutdown
      }

      "A transaction tree factory" when {

        "everything is ok" must {
          forEvery(factory.standardHappyCases) { example =>
            lazy val treeFactory = createTransactionTreeFactory()

            s"create the correct views for: $example" in {
              createTransactionTree(
                treeFactory,
                example.wellFormedUnsuffixedTransaction,
                successfulLookup(example),
                example.keyResolver.asCidOptionMap,
              ).value.flatMap(_ should equal(Right(example.transactionTree)))
            }
          }

          "record external call results from non-root same-view exercise nodes" in {
            val devFactory = new ExampleTransactionFactory(
              versionOverride = Some(ProtocolVersion.dev)
            )(
              psid = factory.psid.copy(protocolVersion = ProtocolVersion.dev),
              cantonContractIdVersion = contractIdVersion,
            )
            val treeFactory = createTransactionTreeFactory(devFactory)
            val example = devFactory.MultipleRootsAndSimpleViewNesting
            val nodeId = LfNodeId(5)

            createTransactionTree(
              treeFactory,
              withExternalCallResults(example, nodeId, ImmArray(externalCallResult)),
              successfulLookup(example),
              example.keyResolver.asCidOptionMap,
              snapshot = devFactory.topologySnapshot,
              exampleFactory = devFactory,
            ).value.map { result =>
              val tree = result.value
              tree.rootViews.unblindedElements should have size 2
              val view1 = tree.rootViews.unblindedElements.drop(1).headOption.value
              val record = view1.viewParticipantData.tryUnwrap.externalCallResults.toSeq.loneElement

              record.result shouldBe externalCallResult
              record.nodeId shouldBe nodeId
              record.callIndex shouldBe 0
              record.checkingParties shouldBe Set(
                ExampleTransactionFactory.submitter,
                ExampleTransactionFactory.signatory,
              )
            }
          }

          "reconstruct root external call records with the submitting participant admin party" in {
            val devFactory = new ExampleTransactionFactory(
              versionOverride = Some(ProtocolVersion.dev)
            )(
              psid = factory.psid.copy(protocolVersion = ProtocolVersion.dev),
              cantonContractIdVersion = contractIdVersion,
            )
            val submittingTreeFactory = createTransactionTreeFactory(devFactory)
            val validatingTreeFactory = createTransactionTreeFactory(
              devFactory,
              ExampleTransactionFactory.observerParticipant,
            )
            val example = devFactory.SingleExercise(devFactory.deriveNodeSeed(0))
            val transaction =
              withExternalCallResults(example, LfNodeId(0), ImmArray(externalCallResult))

            createTransactionTree(
              submittingTreeFactory,
              transaction,
              successfulLookup(example),
              example.keyResolver.asCidOptionMap,
              snapshot = devFactory.topologySnapshot,
              exampleFactory = devFactory,
            ).value.flatMap { result =>
              val tree = result.value
              val submittedView = tree.rootViews.unblindedElements.loneElement

              validatingTreeFactory
                .tryReconstruct(
                  transaction = transaction,
                  rootPosition = tree.viewPosition(submittedView.viewHash.toRootHash).value,
                  mediator = devFactory.mediatorGroup,
                  submittingParticipantO = Some(ExampleTransactionFactory.submittingParticipant),
                  salts = submittingTreeFactory.saltsFromView(submittedView),
                  transactionUuid = devFactory.transactionUuid,
                  topologySnapshot = devFactory.topologySnapshot,
                  contractOfId = successfulLookup(example),
                  rbContext = RollbackContext.empty,
                  legacyKeyResolver = example.keyResolver,
                  absolutizer = devFactory.absolutizer(tree.updateId),
                )
                .failOnShutdown
                .value
                .map { reconstruction =>
                  val (reconstructedView, _) = reconstruction.value
                  val record =
                    reconstructedView.viewParticipantData.tryUnwrap.externalCallResults.toSeq.loneElement

                  record.checkingParties shouldBe Set(ExampleTransactionFactory.submitter)
                }
            }
          }
        }

        "a contract lookup fails" must {
          lazy val errorMessage = "Test error message"
          lazy val treeFactory = createTransactionTreeFactory()

          lazy val example = factory.SingleExercise(
            factory.deriveNodeSeed(0)
          ) // pick an example that needs divulgence of absolute ids

          "reject the input" in {
            createTransactionTree(
              treeFactory,
              example.wellFormedUnsuffixedTransaction,
              failedLookup(errorMessage),
              example.keyResolver.asCidOptionMap,
            ).value.flatMap(
              _ shouldEqual Left(
                ContractLookupError(example.absolutizedContractId, errorMessage)
              )
            )
          }
        }

        "empty actAs set is empty" must {
          lazy val treeFactory = createTransactionTreeFactory()

          "reject the input" in {
            val example = factory.standardHappyCases.headOption.value
            createTransactionTree(
              treeFactory,
              example.wellFormedUnsuffixedTransaction,
              successfulLookup(example),
              example.keyResolver.asCidOptionMap,
              actAs = List.empty,
            ).value
              .flatMap(
                _ should equal(Left(SubmitterMetadataError("The actAs set must not be empty.")))
              )
          }
        }

        "checking package vettings" must {
          lazy val treeFactory = createTransactionTreeFactory()
          "fail if the main package is not vetted" in {
            val example = factory.standardHappyCases(2)
            createTransactionTree(
              treeFactory,
              example.wellFormedUnsuffixedTransaction,
              successfulLookup(example),
              example.keyResolver.asCidOptionMap,
              snapshot = defaultTestingTopology.withPackages(Map.empty).build().topologySnapshot(),
            ).value.flatMap(_ should matchPattern { case Left(UnknownPackageError(_)) => })
          }

          "accept a package if it has a non-vetted dependency" onlyRunWithOrGreaterThan ProtocolVersion.v35 in {
            val example = factory.standardHappyCases(2)
            createTransactionTree(
              treeFactory,
              example.wellFormedUnsuffixedTransaction,
              successfulLookup(example),
              example.keyResolver.asCidOptionMap,
              snapshot = defaultTestingIdentityFactory.topologySnapshot(
                packageDependencyResolver = TestPackageDependencyResolver
              ),
            ).value.flatMap(_ should equal(Right(example.transactionTree)))
          }

          "fail gracefully if the present participant is misconfigured and somehow doesn't have a package that it should have" in {
            val example = factory.standardHappyCases(2)
            for {
              err <- createTransactionTree(
                treeFactory,
                example.wellFormedUnsuffixedTransaction,
                successfulLookup(example),
                example.keyResolver.asCidOptionMap,
                snapshot = defaultTestingIdentityFactory.topologySnapshot(
                  packageDependencyResolver = MisconfiguredPackageDependencyResolver
                ),
              ).value
            } yield {
              inside(err) { case Left(UnknownPackageError(unknownTo)) =>
                forEvery(unknownTo) {
                  _.packageId shouldBe ExampleTransactionFactory.packageId
                }
                unknownTo should not be empty
              }
            }
          }
        }
      }
    }
  }

  object TestPackageDependencyResolver extends PackageDependencyResolver {
    val exampleDependency: IdString.PackageId = PackageId.assertFromString("example-dependency")
    override def resolvePackagesAndDependencies(packages: Set[PackageId])(implicit
        traceContext: TraceContext
    ): Either[(ParticipantId, Set[PackageId]), ResolvedPackagesAndDependencies] =
      if (packages.contains(ExampleTransactionFactory.packageId))
        Right(ResolvedPackagesAndDependencies(packages, Set(exampleDependency)))
      else Right(ResolvedPackagesAndDependencies(packages, Set.empty))
  }

  object MisconfiguredPackageDependencyResolver extends PackageDependencyResolver {
    private val participantId = ParticipantId("MisconfiguredPackageDependencyResolver")

    override def resolvePackagesAndDependencies(packages: Set[PackageId])(implicit
        traceContext: TraceContext
    ): Either[(ParticipantId, Set[PackageId]), ResolvedPackagesAndDependencies] = Left(
      participantId -> packages
    )
  }
}
