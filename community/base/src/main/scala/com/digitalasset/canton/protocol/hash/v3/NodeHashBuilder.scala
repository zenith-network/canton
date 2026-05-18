// Copyright (c) 2026 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.protocol.hash.v3

import com.digitalasset.canton.crypto.HashPurpose
import com.digitalasset.canton.protocol.hash.HashTracer
import com.digitalasset.canton.protocol.{LfHash, hash}
import com.digitalasset.canton.version.HashingSchemeVersion
import com.digitalasset.daml.lf.transaction.{
  ExternalCallResult,
  GlobalKeyWithMaintainers,
  Node,
  NodeId,
  SerializationVersion,
}

/** Node hash builder for HashingSchemeVersion.V3
  *
  * At the node level, V3 makes the following additions to the common builder:
  *   - Create: keyOpt
  *   - Fetch: keyOpt, byKey
  *   - Exercise: keyOpt, byKey, externalCallResults
  *   - QueryByKey: new node This class extends NodeHashBuilderV3 and overrides relevant methods to
  *     add new node fields that mush be hashed in V3.
  */
private[hash] class NodeHashBuilder(
    purpose: HashPurpose,
    hashTracer: HashTracer,
    enforceNodeSeedForCreateNodes: Boolean,
) extends hash.NodeHashBuilderCommon(
      purpose,
      hashTracer,
      enforceNodeSeedForCreateNodes,
      HashingSchemeVersion.V3,
    ) {

  override protected val hashingSchemeVersion: HashingSchemeVersion = HashingSchemeVersion.V3

  override private[hash] def newBuilder(hashTracer: HashTracer): NodeHashBuilder =
    new NodeHashBuilder(purpose, hashTracer, enforceNodeSeedForCreateNodes)

  private def addGlobalKeyWithMaintainers(
      globalKeyWithMaintainers: GlobalKeyWithMaintainers
  ): this.type = {
    val globalKey = globalKeyWithMaintainers.globalKey
    val maintainers = globalKeyWithMaintainers.maintainers

    addContext("Key")
      .withContext("Package Name")(_.addString(globalKey.packageName))
      .withContext("Template Id")(_.addIdentifier(globalKey.templateId))
      .withContext("Value")(_.addTypedValue(globalKey.key))
      .addLfHash(globalKey.hash, "Global Key Hash")
      .addContext("Key Maintainers")
      .addStringSet(maintainers)
  }

  private def addExternalCallResult(result: ExternalCallResult): this.type =
    addContext("External Call Result")
      .withContext("Extension Id")(_.addString(result.extensionId))
      .withContext("Function Id")(_.addString(result.functionId))
      .withContext("Config")(_.addByteString(result.config.toByteString))
      .withContext("Input")(_.addByteString(result.input.toByteString))
      .withContext("Output")(_.addByteString(result.output.toByteString))

  private val addQueryByKeyNode: Node.QueryByKey => this.type = {
    case Node.QueryByKey(packageName, templateId, exhaustive, key, result, version) =>
      addContext("QueryByKey Node")
        .withContext("Node Version")(_.addString(SerializationVersion.toProtoValue(version)))
        .addByte(hash.NodeHashBuilder.NodeTag.QueryByKey.tag, _ => "QueryByKey Node Tag")
        .withContext("PackageName")(_.addString(packageName))
        .withContext("TemplateId")(_.addIdentifier(templateId))
        .withContext("Exhaustive")(_.addBool(exhaustive))
        .withContext("Key")(_.addGlobalKeyWithMaintainers(key))
        .withContext("Result")(
          _.addIterator(result.iterator, result.size)((builder, cid) => builder.addCid(cid))
        )
  }

  override protected def addCreateNode(nodeSeed: Option[LfHash]): Node.Create => this.type = node =>
    {
      super
        .addCreateNode(nodeSeed)(node)
        .withContext("Key")(
          _.addOptional(node.keyOpt, _.addGlobalKeyWithMaintainers)
        )
    }

  override protected def addExerciseNodeNoChildren(
      nodeSeed: LfHash
  ): Node.Exercise => this.type = node => {
    val builder = super
      .addExerciseNodeNoChildren(nodeSeed)(node)
      .withContext("By Key")(
        _.addBool(node.byKey)
      )
      .withContext("Key")(
        _.addOptional(node.keyOpt, _.addGlobalKeyWithMaintainers)
      )

    if (node.externalCallResults.nonEmpty)
      builder.withContext("External Call Results")(
        _.addArray(node.externalCallResults)((builder, result) =>
          builder.addExternalCallResult(result)
        )
      )
    else builder
  }

  override def addFetchNode(node: Node.Fetch): this.type =
    super
      .addFetchNode(node)
      .withContext("By Key")(
        _.addBool(node.byKey)
      )
      .withContext("Key")(
        _.addOptional(node.keyOpt, _.addGlobalKeyWithMaintainers)
      )

  override protected def addNode(
      node: Node,
      nodeSeedO: Option[LfHash],
      nodes: Map[NodeId, Node],
      nodeSeeds: Map[NodeId, LfHash],
  ): this.type =
    node match {
      // Check first for queryByKey nodes and handle them before falling back to the V2 builder where they would be rejected
      case queryByKey: Node.QueryByKey => addQueryByKeyNode(queryByKey)
      case _ => super.addNode(node, nodeSeedO, nodes, nodeSeeds)
    }
}
