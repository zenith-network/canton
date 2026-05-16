// Copyright (c) 2026 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.data

import cats.syntax.either.*
import com.digitalasset.canton.crypto.{HashOps, Salt}
import com.digitalasset.canton.data.ViewParticipantData.InvalidViewParticipantData
import com.digitalasset.canton.protocol.*
import com.digitalasset.canton.protocol.v32
import com.digitalasset.canton.protocol.v30.ActionDescription.FetchActionDescription
import com.digitalasset.canton.util.ShowUtil.*
import com.digitalasset.canton.version.ProtocolVersion
import com.digitalasset.canton.{
  BaseTest,
  HasExecutionContext,
  LfPartyId,
  LfPackageId,
  LfVersioned,
  ProtoDeserializationError,
  ProtocolVersionChecksAnyWordSpec,
}
import com.digitalasset.daml.lf.data.{Bytes, ImmArray}
import com.digitalasset.daml.lf.transaction.ExternalCallResult
import com.google.protobuf.{ByteString as ProtoByteString, CodedOutputStream}
import org.scalatest.wordspec.AnyWordSpec

class TransactionViewTest
    extends AnyWordSpec
    with BaseTest
    with HasExecutionContext
    with ProtocolVersionChecksAnyWordSpec {

  private val factory = new ExampleTransactionFactory()()

  private val hashOps: HashOps = factory.cryptoOps

  private val contractInst: LfThinContractInst = ExampleTransactionFactory.contractInstance()

  private val cantonContractIdVersion: CantonContractIdV1Version = CantonContractIdVersion.maxV1
  private val createdId: LfContractId =
    cantonContractIdVersion.fromDiscriminator(
      ExampleTransactionFactory.lfHash(3),
      ExampleTransactionFactory.unicum(0),
    )
  private val absoluteId: LfContractId = ExampleTransactionFactory.suffixedId(0, 0)
  private val otherAbsoluteId: LfContractId = ExampleTransactionFactory.suffixedId(1, 1)
  private val salt: Salt = factory.transactionSalt
  private val nodeSeed: LfHash = ExampleTransactionFactory.lfHash(1)

  private val defaultPackagePreference = Set(ExampleTransactionFactory.packageId)
  private val externalCallResult: ExternalCallResult =
    ExternalCallResult(
      extensionId = "extension",
      functionId = "function",
      config = Bytes.fromStringUtf8("config"),
      input = Bytes.fromStringUtf8("input"),
      output = Bytes.fromStringUtf8("output"),
    )
  private val otherExternalCallOutput: ExternalCallResult =
    externalCallResult.copy(output = Bytes.fromStringUtf8("other-output"))
  private val externalCallCheckingParties =
    Set(ExampleTransactionFactory.submitter, ExampleTransactionFactory.signatory)

  private def viewExternalCallResult(
      nodeId: LfNodeId,
      result: ExternalCallResult = externalCallResult,
      callIndex: Int = 0,
      checkingParties: Set[LfPartyId] = externalCallCheckingParties,
  ): ViewParticipantData.ViewExternalCallResult =
    ViewParticipantData.ViewExternalCallResult(result, nodeId, callIndex, checkingParties)

  private def externalCallResultProto(
      includeNodeId: Boolean = true,
      includeCallIndex: Boolean = true,
  ): v32.ViewExternalCallResult = {
    val out = ProtoByteString.newOutput()
    val output = CodedOutputStream.newInstance(out)
    output.writeString(1, externalCallResult.extensionId)
    output.writeString(2, externalCallResult.functionId)
    output.writeBytes(3, externalCallResult.config.toByteString)
    output.writeBytes(4, externalCallResult.input.toByteString)
    output.writeBytes(5, externalCallResult.output.toByteString)
    if (includeNodeId) output.writeInt32(6, 7)
    if (includeCallIndex) output.writeInt32(7, 1)
    externalCallCheckingParties.foreach(party => output.writeString(8, party))
    output.flush()

    v32.ViewExternalCallResult.parseFrom(out.toByteString.newCodedInput())
  }

  private val defaultActionDescription: ActionDescription =
    ActionDescription.tryFromLfActionNode(
      ExampleTransactionFactory.createNode(createdId, contractInst),
      Some(ExampleTransactionFactory.lfHash(5)),
      defaultPackagePreference,
    )

  forEvery(factory.standardHappyCases) { example =>
    s"The views of $example" when {

      forEvery(example.viewWithSubviews.zipWithIndex) { case ((view, subviews), index) =>
        s"processing $index-th view" can {
          "be folded" in {
            val foldedSubviews =
              view.foldLeft(Seq.newBuilder[TransactionView])((acc, v) => acc += v)

            foldedSubviews.result() should equal(subviews)
          }

          "be flattened" in {
            view.flatten should equal(subviews)
          }
        }
      }
    }
  }

  "A view" when {
    val firstSubviewIndex = TransactionSubviews.indices(1).head.toString

    "a child view has the same view common data" must {
      val view = factory.SingleCreate(seed = ExampleTransactionFactory.lfHash(3)).view0
      val subViews = TransactionSubviews(Seq(view))(testedProtocolVersion, factory.cryptoOps)
      "reject creation" in {
        TransactionView.create(hashOps)(
          view.viewCommonData,
          view.viewParticipantData,
          subViews,
          testedProtocolVersion,
        ) shouldEqual Left(
          s"The subview with index $firstSubviewIndex has equal viewCommonData to a parent."
        )
      }
    }

    "a child view has package preferences not in the parent" must {

      val unexpectedPackage = LfPackageId.assertFromString("u1")
      val view = factory.SingleExercise(seed = ExampleTransactionFactory.lfHash(3)).view0

      "reject creation if child exercise based view is different from its parent" in {

        val subview =
          TransactionView.Optics.viewParticipantDataUnsafe
            .modify { vpd =>
              val actionDescription = vpd.tryUnwrap.actionDescription.toProtoV30
              actionDescription.getExercise.withPackagePreference(Seq(unexpectedPackage))
              val exercise = actionDescription.withExercise(
                actionDescription.getExercise.withPackagePreference(Seq(unexpectedPackage))
              )
              vpd.tryUnwrap.copy(actionDescription = ActionDescription.fromProtoV30(exercise).value)
            }(view)

        val subViews = TransactionSubviews(Seq(subview))(testedProtocolVersion, factory.cryptoOps)

        TransactionView
          .create(hashOps)(
            view.viewCommonData,
            view.viewParticipantData,
            subViews,
            testedProtocolVersion,
          )
          .left
          .value shouldBe s"Detected unexpected exercise package preference: $unexpectedPackage at $firstSubviewIndex"
      }

      "reject creation if child fetch based view is different from its parent" in {

        val subview =
          TransactionView.Optics.viewParticipantDataUnsafe
            .modify { vpd =>
              val actionDescription = vpd.tryUnwrap.actionDescription.toProtoV30
              val ex = actionDescription.getExercise
              val fetch = actionDescription.withFetch(
                FetchActionDescription(
                  inputContractId = ex.inputContractId,
                  actors = ex.actors,
                  byKey = false,
                  templateId = s"$unexpectedPackage:module:template",
                  interfaceId = Some("ifPkg:module:template"),
                )
              )
              vpd.tryUnwrap.copy(actionDescription = ActionDescription.fromProtoV30(fetch).value)
            }(view)

        val subViews = TransactionSubviews(Seq(subview))(testedProtocolVersion, factory.cryptoOps)

        TransactionView
          .create(hashOps)(
            view.viewCommonData,
            view.viewParticipantData,
            subViews,
            testedProtocolVersion,
          )
          .left
          .value shouldBe s"Detected unexpected fetch package preference: $unexpectedPackage at $firstSubviewIndex"
      }

    }
  }

  "A view participant data" when {

    def create(
        actionDescription: ActionDescription = defaultActionDescription,
        consumed: Set[LfContractId] = Set.empty,
        coreInputs: Map[LfContractId, GenContractInstance] = Map.empty,
        createdIds: Seq[LfContractId] = Seq(createdId),
        archivedInSubviews: Set[LfContractId] = Set.empty,
        resolvedKeys: Map[LfGlobalKey, LfVersioned[KeyResolutionWithMaintainers]] = Map.empty,
        externalCallResults: ImmArray[ViewParticipantData.ViewExternalCallResult] = ImmArray.Empty,
        protocolVersion: ProtocolVersion = testedProtocolVersion,
    ): Either[String, ViewParticipantData] = {

      val created = createdIds.map { id =>
        val contract = ExampleContractFactory.build(overrideContractId = Some(id))
        CreatedContract.tryCreate(contract, consumed.contains(id), rolledBack = false)
      }
      val coreInputs2 = coreInputs.transform { (id, contract) =>
        InputContract(contract, consumed.contains(id))
      }

      ViewParticipantData
        .create(hashOps)(
          coreInputs2,
          created,
          archivedInSubviews,
          resolvedKeys,
          actionDescription,
          RollbackContext.empty,
          salt,
          protocolVersion,
          externalCallResults,
        )
        .flatMap { data =>
          // Return error message if root action is not valid
          Either
            .catchOnly[InvalidViewParticipantData](data.rootAction)
            .bimap(ex => ex.message, _ => data)
        }
    }

    "a contract is created twice" must {
      "reject creation" in {
        create(createdIds = Seq(createdId, createdId)).left.value should
          startWith regex "createdCore contains the contract id .* multiple times at indices 0, 1"
      }
    }
    "a used contract has an inconsistent id" must {
      "reject creation" in {
        val usedContract = ExampleContractFactory.build(overrideContractId = Some(otherAbsoluteId))
        create(coreInputs = Map(absoluteId -> usedContract)).left.value should startWith(
          "Inconsistent ids for used contract: "
        )
      }
    }
    "an overlap between archivedInSubview and coreCreated" must {
      "reject creation" in {
        create(
          createdIds = Seq(createdId),
          archivedInSubviews = Set(createdId),
        ).left.value should startWith(
          "Contract created in a subview are also created in the core: "
        )
      }
    }
    "an overlap between archivedInSubview and coreInputs" must {
      "reject creation" in {
        val usedContract = ExampleContractFactory.build(overrideContractId = Some(absoluteId))
        create(
          coreInputs = Map(absoluteId -> usedContract),
          archivedInSubviews = Set(absoluteId),
        ).left.value should startWith("Contracts created in a subview overlap with core inputs: ")
      }
    }
    "the created contract of the root action is not declared first" must {
      "reject creation" in {
        create(createdIds = Seq.empty).left.value should startWith(
          "No created core contracts declared for a view that creates contract"
        )
      }
      "reject creation with other contract ids" in {
        val otherCantonId =
          cantonContractIdVersion.fromDiscriminator(
            ExampleTransactionFactory.lfHash(3),
            ExampleTransactionFactory.unicum(1),
          )
        create(createdIds = Seq(otherCantonId, createdId)).left.value should startWith(
          show"View with root action Create $createdId declares $otherCantonId as first created core contract."
        )
      }
    }
    "the used contract of the root action is not declared" must {

      "reject creation with exercise action" in {
        create(
          actionDescription = ActionDescription.tryFromLfActionNode(
            ExampleTransactionFactory.exerciseNodeWithoutChildren(absoluteId),
            Some(nodeSeed),
            defaultPackagePreference,
          )
        ).left.value should startWith(
          show"Input contract $absoluteId of the Exercise root action is not declared as core input."
        )
      }

      "reject creation with fetch action" in {

        create(
          actionDescription = ActionDescription.tryFromLfActionNode(
            ExampleTransactionFactory.fetchNode(
              absoluteId,
              Set(ExampleTransactionFactory.submitter),
            ),
            None,
            defaultPackagePreference,
          )
        ).left.value should startWith(
          show"Input contract $absoluteId of the Fetch root action is not declared as core input."
        )
      }

    }

    "external call results have duplicate occurrence identities" must {
      "reject creation" in {
        create(
          externalCallResults = ImmArray(
            viewExternalCallResult(nodeId = LfNodeId(7), callIndex = 1),
            viewExternalCallResult(
              nodeId = LfNodeId(7),
              callIndex = 1,
              result = externalCallResult.copy(functionId = "other-function"),
            ),
          ),
          protocolVersion = ProtocolVersion.dev,
        ).left.value shouldBe
          "externalCallResults contains duplicate occurrence (node id 7, call index 1)"
      }
    }

    "deserialized" must {

      "reconstruct unkeyed view participant data" in {

        val usedContract = ExampleContractFactory.build(
          overrideContractId = Some(absoluteId)
        )
        val vpd = create(
          consumed = Set(absoluteId),
          createdIds = Seq(createdId),
          coreInputs = Map(absoluteId -> usedContract),
          archivedInSubviews = Set(otherAbsoluteId),
        ).value

        ViewParticipantData
          .fromByteString(testedProtocolVersion, hashOps)(
            vpd.getCryptographicEvidence
          )
          .map(_.unwrap) shouldBe Right(Right(vpd))
      }

      "reconstruct the original keyed view participant data" onlyRunWithOrGreaterThan ProtocolVersion.v35 in {

        val key = ExampleTransactionFactory.globalKeyWithMaintainers()

        val usedContract = ExampleContractFactory.build(
          overrideContractId = Some(absoluteId),
          keyOpt = Some(key.unversioned),
        )
        val vpd = create(
          consumed = Set(absoluteId),
          createdIds = Seq(createdId),
          coreInputs = Map(absoluteId -> usedContract),
          archivedInSubviews = Set(otherAbsoluteId),
          resolvedKeys = Map(
            ExampleTransactionFactory.defaultGlobalKey ->
              LfVersioned(
                key.version,
                KeyResolutionWithMaintainers(
                  Vector(usedContract.contractId),
                  key.unversioned.maintainers,
                ),
              )
          ),
        ).value

        ViewParticipantData
          .fromByteString(testedProtocolVersion, hashOps)(
            vpd.getCryptographicEvidence
          )
          .map(_.unwrap) shouldBe Right(Right(vpd))
      }

      "reconstruct dev external call results" in {
        val vpd = create(
          externalCallResults = ImmArray(
            viewExternalCallResult(
              nodeId = LfNodeId(7),
              callIndex = 1,
              checkingParties = externalCallCheckingParties,
            )
          ),
          protocolVersion = ProtocolVersion.dev,
        ).value

        ViewParticipantData
          .fromByteString(ProtocolVersion.dev, hashOps)(
            vpd.getCryptographicEvidence
          )
          .map(_.unwrap) shouldBe Right(Right(vpd))
      }

      "reconstruct older view participant data with no external call results" in {
        val vpd = create(protocolVersion = ProtocolVersion.v35).value

        ViewParticipantData
          .fromByteString(ProtocolVersion.v35, hashOps)(
            vpd.getCryptographicEvidence
          )
          .map(_.unwrap.map(_.externalCallResults)) shouldBe Right(Right(ImmArray.Empty))
      }

      "reject an external call result without node_id" in {
        ViewParticipantData.ViewExternalCallResult
          .fromProtoV32(externalCallResultProto(includeNodeId = false))
          .left
          .value shouldBe ProtoDeserializationError.FieldNotSet("node_id")
      }

      "reject an external call result without call_index" in {
        ViewParticipantData.ViewExternalCallResult
          .fromProtoV32(externalCallResultProto(includeCallIndex = false))
          .left
          .value shouldBe ProtoDeserializationError.FieldNotSet("call_index")
      }
    }

    "visible external call results" must {
      def withExternalCallResults(
          view: TransactionView,
          results: ImmArray[ViewParticipantData.ViewExternalCallResult],
      ): TransactionView =
        TransactionView.Optics.viewParticipantDataUnsafe
          .modify(vpd => vpd.tryUnwrap.copy(externalCallResults = results))(view)

      "tolerate a covered exact duplicate in a child view" in {
        val devFactory =
          new ExampleTransactionFactory(versionOverride = Some(ProtocolVersion.dev))()
        val parent = withExternalCallResults(
          devFactory.SingleExercise(seed = ExampleTransactionFactory.lfHash(30)).view0,
          ImmArray(
            viewExternalCallResult(
              nodeId = LfNodeId(1),
              checkingParties = Set(ExampleTransactionFactory.signatory),
            )
          ),
        )
        val child = withExternalCallResults(
          devFactory.SingleFetch().view0,
          ImmArray(viewExternalCallResult(nodeId = LfNodeId(2))),
        )

        TransactionView
          .create(devFactory.cryptoOps)(
            parent.viewCommonData,
            parent.viewParticipantData,
            TransactionSubviews(Seq(child))(ProtocolVersion.dev, devFactory.cryptoOps),
            ProtocolVersion.dev,
          )
          .value
          .viewParticipantData
          .tryUnwrap
          .externalCallResults shouldBe parent.viewParticipantData.tryUnwrap.externalCallResults
      }

      "reject the same semantic external call with different outputs" in {
        val devFactory =
          new ExampleTransactionFactory(versionOverride = Some(ProtocolVersion.dev))()
        val parent = withExternalCallResults(
          devFactory.SingleExercise(seed = ExampleTransactionFactory.lfHash(31)).view0,
          ImmArray(viewExternalCallResult(nodeId = LfNodeId(1))),
        )
        val child = withExternalCallResults(
          devFactory.SingleFetch().view0,
          ImmArray(viewExternalCallResult(result = otherExternalCallOutput, nodeId = LfNodeId(2))),
        )

        TransactionView
          .create(devFactory.cryptoOps)(
            parent.viewCommonData,
            parent.viewParticipantData,
            TransactionSubviews(Seq(child))(ProtocolVersion.dev, devFactory.cryptoOps),
            ProtocolVersion.dev,
          )
          .left
          .value should startWith("External call result disagreement for")
      }

      "reject the same semantic external call with different outputs across visible children when parent participant data is blinded" in {
        val devFactory =
          new ExampleTransactionFactory(versionOverride = Some(ProtocolVersion.dev))()
        val example = devFactory.MultipleRootsAndViewNestings
        val child0 = withExternalCallResults(
          example.view10,
          ImmArray(viewExternalCallResult(nodeId = LfNodeId(10))),
        )
        val child1 = withExternalCallResults(
          example.view11,
          ImmArray(viewExternalCallResult(result = otherExternalCallOutput, nodeId = LfNodeId(11))),
        )

        TransactionView
          .create(devFactory.cryptoOps)(
            example.view1.viewCommonData,
            example.view1.viewParticipantData.blindFully,
            TransactionSubviews(Seq(child0, child1))(ProtocolVersion.dev, devFactory.cryptoOps),
            ProtocolVersion.dev,
          )
          .left
          .value should startWith("External call result disagreement for")
      }

      "reject the same semantic external call with a different output in a grandchild view" in {
        val devFactory =
          new ExampleTransactionFactory(versionOverride = Some(ProtocolVersion.dev))()
        val example = devFactory.MultipleRootsAndViewNestings
        val parent = withExternalCallResults(
          example.view1,
          ImmArray(viewExternalCallResult(nodeId = LfNodeId(1))),
        )
        val grandchild = withExternalCallResults(
          example.view110,
          ImmArray(
            viewExternalCallResult(result = otherExternalCallOutput, nodeId = LfNodeId(110))
          ),
        )
        val child = TransactionView
          .create(devFactory.cryptoOps)(
            example.view11.viewCommonData,
            example.view11.viewParticipantData,
            TransactionSubviews(Seq(grandchild))(ProtocolVersion.dev, devFactory.cryptoOps),
            ProtocolVersion.dev,
          )
          .value

        TransactionView
          .create(devFactory.cryptoOps)(
            parent.viewCommonData,
            parent.viewParticipantData,
            TransactionSubviews(Seq(child))(ProtocolVersion.dev, devFactory.cryptoOps),
            ProtocolVersion.dev,
          )
          .left
          .value should startWith("External call result disagreement for")
      }

      "reject the same semantic external call with different outputs across root views" in {
        val devFactory =
          new ExampleTransactionFactory(versionOverride = Some(ProtocolVersion.dev))()
        val example = devFactory.MultipleRoots
        val root0 = withExternalCallResults(
          example.rootViews(0),
          ImmArray(viewExternalCallResult(nodeId = LfNodeId(0))),
        )
        val root1 = withExternalCallResults(
          example.rootViews(1),
          ImmArray(viewExternalCallResult(result = otherExternalCallOutput, nodeId = LfNodeId(1))),
        )
        val rootViews = example.rootViews.updated(0, root0).updated(1, root1)

        GenTransactionTree
          .create(devFactory.cryptoOps)(
            example.transactionTree.submitterMetadata,
            example.transactionTree.commonMetadata,
            example.transactionTree.participantMetadata,
            MerkleSeq.fromSeq(devFactory.cryptoOps, ProtocolVersion.dev)(rootViews),
          )
          .left
          .value should startWith("External call result disagreement for")
      }
    }
  }
}
