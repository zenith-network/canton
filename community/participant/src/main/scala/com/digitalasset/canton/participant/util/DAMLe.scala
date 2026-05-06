// Copyright (c) 2026 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.participant.util

import cats.data.EitherT
import cats.syntax.either.*
import com.digitalasset.canton.data.{CantonTimestamp, LedgerTimeBoundaries}
import com.digitalasset.canton.interactive.InteractiveSubmissionEnricher
import com.digitalasset.canton.lifecycle.FutureUnlessShutdown
import com.digitalasset.canton.lifecycle.FutureUnlessShutdownImpl.*
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.participant.protocol.EngineController.GetEngineAbortStatus
import com.digitalasset.canton.participant.store.ReplayContractLookup
import com.digitalasset.canton.participant.util.DAMLe.*
import com.digitalasset.canton.platform.apiserver.execution.ExternalCallHandler
import com.digitalasset.canton.protocol.*
import com.digitalasset.canton.topology.ParticipantId
import com.digitalasset.canton.topology.client.TopologySnapshot
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.ContractValidator.ContractAuthenticatorFn
import com.digitalasset.canton.util.PackageConsumer.PackageResolver
import com.digitalasset.canton.util.Thereafter.syntax.*
import com.digitalasset.canton.{LfCommand, LfGlobalKeyMapping, LfPackageId, LfPartyId}
import com.digitalasset.daml.lf.VersionRange
import com.digitalasset.daml.lf.data.Ref.{PackageId, PackageName}
import com.digitalasset.daml.lf.data.{Bytes as LfBytes, ImmArray, Ref}
import com.digitalasset.daml.lf.engine.ResultNeedContract.Response
import com.digitalasset.daml.lf.engine.{Enricher as _, *}
import com.digitalasset.daml.lf.language.LanguageVersion.v2_dev
import com.digitalasset.daml.lf.language.{Ast, LanguageVersion}
import com.digitalasset.daml.lf.transaction.ExternalCallResult
import com.digitalasset.daml.lf.transaction.{
  FatContractInstance,
  NeedKeyProgression,
  NextGenContractStateMachine as ContractStateMachine,
  SerializationVersion,
}
import com.digitalasset.daml.lf.value.ContractIdVersion

import java.nio.file.Path
import scala.annotation.tailrec
import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success}

object DAMLe {
  final case class ReInterpretationResult(
      transaction: LfVersionedTransaction,
      metadata: TransactionMetadata,
      legacyKeyResolver: LfGlobalKeyMapping,
      usedPackages: Set[PackageId],
      timeBoundaries: LedgerTimeBoundaries,
  )

  def newEngine(
      enableLfDev: Boolean,
      enableLfBeta: Boolean,
      enableStackTraces: Boolean,
      profileDir: Option[Path] = None,
      snapshotDir: Option[Path] = None,
      iterationsBetweenInterruptions: Long =
        10000, // 10000 is the default value in the engine configuration,
      paranoidMode: Boolean,
      submissionPhaseLogging: EngineLoggingConfig,
      validationPhaseLogging: EngineLoggingConfig,
      loggerFactory: NamedLoggerFactory,
  ): Engine =
    new Engine(
      EngineConfig(
        allowedLanguageVersions = VersionRange(
          LanguageVersion.v2_1,
          maxVersion(enableLfDev, enableLfBeta),
        ),
        // The package store contains only validated packages, so we can skip validation upon loading
        packageValidation = false,
        stackTraceMode = enableStackTraces,
        profileDir = profileDir,
        snapshotDir = snapshotDir,
        forbidLocalContractIds = true,
        iterationsBetweenInterruptions = iterationsBetweenInterruptions,
        paranoid = paranoidMode,
        submissionPhaseLogging = submissionPhaseLogging,
        validationPhaseLogging = validationPhaseLogging,
      ),
      loggerFactory,
    )

  private def maxVersion(enableLfDev: Boolean, enableLfBeta: Boolean) =
    if (enableLfDev) v2_dev
    else if (enableLfBeta) LanguageVersion.earlyAccessLfVersionsRange.max
    else LanguageVersion.stableLfVersionsRange.max

  /** Resolves packages by [[com.digitalasset.daml.lf.data.Ref.PackageId]]. The returned packages
    * must have been validated so that [[com.digitalasset.daml.lf.engine.Engine]] can skip
    * validation.
    */
  private type Enricher[I, O] = I => TraceContext => EitherT[
    FutureUnlessShutdown,
    ReinterpretationError,
    O,
  ]
  type TransactionEnricher = Enricher[LfVersionedTransaction, LfVersionedTransaction]
  type ContractEnricher = Enricher[(FatContractInstance, Set[LfPackageId]), FatContractInstance]

  sealed trait ReinterpretationError extends PrettyPrinting

  final case class EngineError(cause: Error) extends ReinterpretationError {
    override protected def pretty: Pretty[EngineError] = adHocPrettyInstance
  }

  final case class EngineAborted(reason: String) extends ReinterpretationError {
    override protected def pretty: Pretty[EngineAborted] = prettyOfClass(
      param("reason", _.reason.doubleQuoted)
    )
  }

  final case class EnrichmentError(reason: String) extends ReinterpretationError {
    override protected def pretty: Pretty[EnrichmentError] = adHocPrettyInstance
  }

  /** Stored external call results for replay during validation. Key is (extensionId, functionId,
    * index), value is (config, input, output) as data.Bytes. The index is derived from the position
    * in the externalCallResults list.
    */
  type StoredExternalCallResults =
    Map[(String, String, Int), (LfBytes, LfBytes, LfBytes, SerializationVersion)]

  object StoredExternalCallResults {
    val empty: StoredExternalCallResults = Map.empty

    def fromResults(results: ImmArray[ExternalCallResult]): StoredExternalCallResults =
      results.toSeq.zipWithIndex.map { case (result, index) =>
        (result.extensionId, result.functionId, index) ->
          (result.config, result.input, result.output, result.valueSerializationVersion)
      }.toMap
  }

  private[participant] def externalCallEvidenceMismatch(
      stored: (LfBytes, LfBytes, LfBytes, SerializationVersion),
      requestedConfigHash: String,
      requestedInput: String,
      requestedValueSerializationVersion: SerializationVersion,
      computedOutput: Option[String],
  ): Option[String] = {
    val (storedConfig, storedInput, storedOutput, storedSerializationVersion) = stored
    val storedConfigHex = storedConfig.toHexString
    val storedInputHex = storedInput.toHexString
    val storedOutputHex = storedOutput.toHexString
    val mismatches = Seq(
      Option.when(storedConfigHex != requestedConfigHash)(
        s"config expected '$storedConfigHex' but engine requested '$requestedConfigHash'"
      ),
      Option.when(storedInputHex != requestedInput)(
        s"input expected '$storedInputHex' but engine requested '$requestedInput'"
      ),
      Option.when(storedSerializationVersion != requestedValueSerializationVersion)(
        s"value serialization version expected '$storedSerializationVersion' but engine requested '$requestedValueSerializationVersion'"
      ),
      computedOutput.filter(_ != storedOutputHex).map { output =>
        s"output expected '$storedOutputHex' but handler computed '$output'"
      },
    ).flatten

    Option.when(mismatches.nonEmpty)(mismatches.mkString("; "))
  }

  trait HasReinterpret {
    def reinterpret(
        contracts: ReplayContractLookup,
        contractAuthenticator: ContractAuthenticatorFn,
        submitters: Set[LfPartyId],
        command: LfCommand,
        topologySnapshot: TopologySnapshot,
        ledgerTime: CantonTimestamp,
        preparationTime: CantonTimestamp,
        rootSeed: Option[LfHash],
        packageResolution: Map[Ref.PackageName, Ref.PackageId],
        expectFailure: Boolean,
        getEngineAbortStatus: GetEngineAbortStatus,
        storedExternalCallResults: StoredExternalCallResults = StoredExternalCallResults.empty,
        isConfirmer: Boolean = false,
    )(implicit traceContext: TraceContext): EitherT[
      FutureUnlessShutdown,
      ReinterpretationError,
      ReInterpretationResult,
    ]
  }

  private final case class ContinuationToken(rest: Vector[FatContractInstance])
      extends NeedKeyProgression.Token

}

/** Represents a Daml runtime instance for interpreting commands. Provides an abstraction for the
  * Daml engine handling requests for contract instance lookup as well as in resolving packages. The
  * recommended execution context is to use a work stealing pool.
  *
  * @param resolvePackage
  *   A resolver for resolving packages
  * @param ec
  *   The execution context where Daml interpretation and validation are execution
  */
class DAMLe(
    participantId: ParticipantId,
    resolvePackage: PackageResolver,
    engine: Engine,
    contractStateMode: ContractStateMachine.Mode,
    protected val loggerFactory: NamedLoggerFactory,
    externalCallHandler: Option[ExternalCallHandler] = None,
)(implicit ec: ExecutionContext)
    extends NamedLogging
    with HasReinterpret {

  import DAMLe.{EngineAborted, EngineError, ReinterpretationError}

  logger.debug(engine.info.show)(TraceContext.empty)

  // TODO(i21582) Because we do not hash suffixed CIDs, we need to disable validation of suffixed CIDs otherwise enrichment
  // will fail. Remove this when we hash and sign suffixed CIDs
  private lazy val engineForEnrichment = new Engine(
    engine.config.copy(forbidLocalContractIds = false),
    loggerFactory,
  )
  private lazy val interactiveSubmissionEnricher = new InteractiveSubmissionEnricher(
    engineForEnrichment,
    new PackageResolver {
      override protected def resolveInternal(
          packageId: PackageId
      )(implicit traceContext: TraceContext): FutureUnlessShutdown[Option[Ast.Package]] =
        resolvePackage
          .resolve(packageId, PackageResolver.ignoreMissingPackage)
          .thereafter {
            case Failure(ex) =>
              logger.error(s"Package resolution failed for [$packageId]", ex)
            case _ => ()
          }
    },
  )

  /** Enrich transaction values by re-hydrating record labels and identifiers
    */
  val enrichTransaction: TransactionEnricher = { transaction => implicit traceContext =>
    EitherT.liftF(interactiveSubmissionEnricher.enrichVersionedTransaction(transaction))
  }

  /** Enrich create node values by re-hydrating record labels and identifiers
    */
  val enrichContract: ContractEnricher = { case (createNode, targetPackageIds) =>
    implicit traceContext =>
      interactiveSubmissionEnricher
        .enrichContract(createNode, targetPackageIds)
        .leftFlatMap(err =>
          EitherT.leftT[FutureUnlessShutdown, FatContractInstance](
            EnrichmentError(err): ReinterpretationError
          )
        )
  }

  override def reinterpret(
      contracts: ReplayContractLookup,
      contractAuthenticator: ContractAuthenticatorFn,
      submitters: Set[LfPartyId],
      command: LfCommand,
      topologySnapshot: TopologySnapshot,
      ledgerTime: CantonTimestamp,
      preparationTime: CantonTimestamp,
      rootSeed: Option[LfHash],
      packageResolution: Map[PackageName, PackageId],
      expectFailure: Boolean,
      getEngineAbortStatus: GetEngineAbortStatus,
      storedExternalCallResults: StoredExternalCallResults = StoredExternalCallResults.empty,
      isConfirmer: Boolean = false,
  )(implicit traceContext: TraceContext): EitherT[
    FutureUnlessShutdown,
    ReinterpretationError,
    ReInterpretationResult,
  ] = {

    def peelAwayRootLevelRollbackNode(
        tx: LfVersionedTransaction
    ): Either[Error, LfVersionedTransaction] =
      if (!expectFailure) {
        Right(tx)
      } else {
        def err(msg: String): Left[Error, LfVersionedTransaction] =
          Left(
            Error.Interpretation(
              Error.Interpretation.Internal("engine.reinterpret", msg, None),
              transactionTrace = None,
            )
          )

        tx.roots.get(0).map(nid => nid -> tx.nodes(nid)) match {
          case Some((rootNid, LfNodeRollback(children))) =>
            children.toSeq match {
              case Seq(singleChildNodeId) =>
                tx.nodes(singleChildNodeId) match {
                  case _: LfActionNode =>
                    Right(
                      LfVersionedTransaction(
                        tx.version,
                        tx.nodes - rootNid,
                        ImmArray(singleChildNodeId),
                      )
                    )
                  case LfNodeRollback(_) =>
                    err(s"Root-level rollback node not expected to parent another rollback node")
                }
              case Seq() => err(s"Root-level rollback node not expected to have no child node")
              case multipleChildNodes =>
                err(
                  s"Root-level rollback node not expect to have more than one child, but found ${multipleChildNodes.length}"
                )
            }
          case nonRollbackNode =>
            err(
              s"Expected failure to be turned into a root rollback node, but encountered $nonRollbackNode"
            )
        }
      }

    val result = engine.reinterpret(
      submitters = submitters,
      command = command,
      nodeSeed = rootSeed,
      preparationTime = preparationTime.toLf,
      ledgerEffectiveTime = ledgerTime.toLf,
      packageResolution = packageResolution,
      contractIdVersion = ContractIdVersion.V1,
      contractStateMode = contractStateMode,
    )

    for {
      txWithMetadata <- EitherT(
        handleResult(
          topologySnapshot,
          ledgerTime,
          contracts,
          contractAuthenticator,
          result,
          getEngineAbortStatus,
          storedExternalCallResults,
          isConfirmer,
        )
      )
      (tx, metadata) = txWithMetadata

      peeledTxE = peelAwayRootLevelRollbackNode(tx).leftMap(EngineError.apply)
      txNoRootRollback <- EitherT.fromEither[FutureUnlessShutdown](
        peeledTxE: Either[ReinterpretationError, LfVersionedTransaction]
      )
    } yield {
      ReInterpretationResult(
        txNoRootRollback,
        TransactionMetadata.fromLf(ledgerTime, metadata),
        metadata.globalKeyMapping,
        metadata.usedPackages,
        LedgerTimeBoundaries(metadata.timeBoundaries),
      )
    }
  }

  private[this] def handleResult[A](
      topologySnapshot: TopologySnapshot,
      ledgerTime: CantonTimestamp,
      contractLookup: ReplayContractLookup,
      contractAuthenticator: ContractAuthenticatorFn,
      result: Result[A],
      getEngineAbortStatus: GetEngineAbortStatus,
      storedExternalCallResults: StoredExternalCallResults,
      isConfirmer: Boolean,
  )(implicit
      traceContext: TraceContext
  ): FutureUnlessShutdown[Either[ReinterpretationError, A]] = {
    val externalCallCounter = new java.util.concurrent.atomic.AtomicInteger(0)

    def handleResultInternal(
        result: Result[A]
    ): FutureUnlessShutdown[Either[ReinterpretationError, A]] = {

      @tailrec
      def iterateOverInterrupts(
          continue: () => Result[A]
      ): Either[EngineAborted, Result[A]] =
        continue() match {
          case ResultInterruption(continue, _) =>
            getEngineAbortStatus().reasonO match {
              case Some(reason) =>
                logger.warn(s"Aborting engine computation, reason = $reason")
                Left(EngineAborted(reason))
              case None => iterateOverInterrupts(continue)
            }

          case otherResult => Right(otherResult)
        }

      result match {
        case ResultNeedPackage(packageId, resume) =>
          resolvePackage
            .resolve(
              packageId,
              PackageResolver.crashOnMissingPackage(topologySnapshot, participantId, ledgerTime),
            )
            .transformWithHandledAborted {
              case Success(pkg) =>
                handleResultInternal(resume(pkg))
              case Failure(ex) =>
                logger.error(s"Package resolution failed for [$packageId]", ex)
                FutureUnlessShutdown.failed(ex)
            }
        case ResultDone(completedResult) => FutureUnlessShutdown.pure(Right(completedResult))

        case ResultNeedKey(key, n, mbToken, resume) =>
          val keyContracts = mbToken match {
            case NeedKeyProgression.Unstarted => contractLookup.lookupKey(key).toVector
            case NeedKeyProgression.InProgress(ContinuationToken(rest)) => rest
            case _ => throw new IllegalStateException("unexpected continuation token")
          }
          val (result, rest) = keyContracts.splitAt(n)
          val hasStarted: NeedKeyProgression.HasStarted =
            if (rest.nonEmpty) NeedKeyProgression.InProgress(ContinuationToken(rest))
            else NeedKeyProgression.Finished
          FutureUnlessShutdown
            .pure(result)
            .flatMap(r => handleResultInternal(resume(r, hasStarted)))

        case ResultNeedContract(acoid, resume) =>
          val response: Response =
            CantonContractIdVersion.extractCantonContractIdVersion(acoid) match {
              case Right(version) =>
                contractLookup.lookupInst(acoid) match {
                  case Some(contract) =>
                    Response.ContractFound(
                      contract,
                      version.contractHashingMethod,
                      hash => contractAuthenticator(contract, hash).isRight,
                    )
                  case None => Response.ContractNotFound
                }
              case Left(_) =>
                Response.UnsupportedContractIdVersion
            }
          FutureUnlessShutdown.pure(response).flatMap(r => handleResultInternal(resume(r)))

        case ResultError(err) => FutureUnlessShutdown.pure(Left(EngineError(err)))
        case ResultInterruption(continue, _) =>
          // Run the interruption loop asynchronously to avoid blocking the calling thread.
          // Using a `Future` as a trampoline also makes the recursive call to `handleResult` stack safe.
          FutureUnlessShutdown.pure(iterateOverInterrupts(continue)).flatMap {
            case Left(abort) => FutureUnlessShutdown.pure(Left(abort))
            case Right(result) => handleResultInternal(result)
          }
        case ResultPrefetch(_, _, resume) =>
          // we do not need to prefetch here as Canton includes the keys as a static map in Phase 3
          handleResultInternal(resume())
        case ResultNeedExternalCall(
              extensionId,
              functionId,
              configHash,
              input,
              inputType,
              outputType,
              valueSerializationVersion,
              resume,
            ) =>
          val currentCallIndex = externalCallCounter.getAndIncrement()
          val externalCallKey = (extensionId, functionId, currentCallIndex)

          def externalCallVerdictError(
              verdict: String,
              message: String,
          ): FutureUnlessShutdown[Either[ReinterpretationError, A]] =
            FutureUnlessShutdown.pure(
              Left(
                EngineError(
                  Error.Interpretation(
                    Error.Interpretation.Internal(
                      "reinterpretation",
                      s"$verdict: $message " +
                        s"(extensionId=$extensionId, functionId=$functionId, callIndex=$currentCallIndex)",
                      None,
                    ),
                    None,
                  )
                )
              )
            )

          (isConfirmer, externalCallHandler) match {
            case (true, Some(handler)) =>
              logger.debug(
                s"Confirmer re-executing external call for extension=$extensionId, function=$functionId, callIndex=$currentCallIndex"
              )
              handler
                .handleExternalCall(
                  extensionId,
                  functionId,
                  configHash,
                  input,
                  "validation",
                  "validation",
                  inputType,
                  outputType,
                  SerializationVersion.toProtoValue(valueSerializationVersion),
                )
                .flatMap {
                  case Right(output) =>
                    storedExternalCallResults.get(externalCallKey) match {
                      case None =>
                        logger.warn(
                          s"Missing stored external call result for confirmer replay: extension=$extensionId, function=$functionId, callIndex=$currentCallIndex"
                        )
                        externalCallVerdictError(
                          "LOCAL_VERDICT_EXTERNAL_CALL_REPLAY_MISSING",
                          "no stored result for confirmer replay. This may indicate transaction tampering or a mismatch between the transaction and its metadata.",
                        )
                      case Some(stored) =>
                        externalCallEvidenceMismatch(
                          stored,
                          configHash,
                          input,
                          valueSerializationVersion,
                          Some(output),
                        ) match {
                          case Some(mismatch) =>
                            logger.warn(
                              s"External call result mismatch for extension=$extensionId, function=$functionId, callIndex=$currentCallIndex: $mismatch"
                            )
                            externalCallVerdictError(
                              "LOCAL_VERDICT_EXTERNAL_CALL_RESULT_MISMATCH",
                              mismatch,
                            )
                          case None =>
                            handleResultInternal(resume(Right(output)))
                        }
                    }
                  case Left(error) =>
                    FutureUnlessShutdown.pure(
                      Left(
                        EngineError(
                          Error.Interpretation(
                            Error.Interpretation.Internal(
                              "reinterpretation",
                              s"LOCAL_VERDICT_EXTERNAL_CALL_FAILED: ${error.message} " +
                                s"(status=${error.statusCode}, extensionId=$extensionId, functionId=$functionId, callIndex=$currentCallIndex" +
                                error.requestId.map(id => s", requestId=$id").getOrElse("") + ")",
                              None,
                            ),
                            None,
                          )
                        )
                      )
                    )
                }

            case _ =>
              storedExternalCallResults.get(externalCallKey) match {
                case None =>
                  externalCallVerdictError(
                    "LOCAL_VERDICT_EXTERNAL_CALL_REPLAY_MISSING",
                    "no stored result for replay. This may indicate transaction tampering or a mismatch between the transaction and its metadata.",
                  )
                case Some(stored) =>
                  externalCallEvidenceMismatch(
                    stored,
                    configHash,
                    input,
                    valueSerializationVersion,
                    computedOutput = None,
                  ) match {
                    case Some(mismatch) =>
                      logger.warn(
                        s"External call replay evidence mismatch for extension=$extensionId, function=$functionId, callIndex=$currentCallIndex: $mismatch"
                      )
                      externalCallVerdictError(
                        "LOCAL_VERDICT_EXTERNAL_CALL_RESULT_MISMATCH",
                        mismatch,
                      )
                    case None =>
                      val output = stored._3.toHexString
                      logger.debug(
                        s"Observer replaying external call result for extension=$extensionId, function=$functionId"
                      )
                      handleResultInternal(resume(Right(output)))
                  }
              }
          }
      }
    }

    handleResultInternal(result)
  }

}
