# Rewrite the external_call OAuth tech spec to match the current codebase

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document must be maintained in accordance with `.agent/PLANS.md`.

## Purpose / Big Picture

After this change, a reader who only has this repository will be able to open `community/participant/EXTERNAL_CALL_OAUTH_TECH_SPEC.md` and get an accurate, implementation-ready description of OAuth support for `external_call`. The rewritten spec will no longer describe transport refactors, startup behavior, or configuration shapes that do not exist in the current code. The observable result is a clean spec diff that aligns with the participant extension code and with the design decisions already settled during review.

## Progress

- [x] (2026-03-25 00:18 +04) Reviewed `.agent/PLANS.md` and confirmed the ExecPlan requirements and file naming rules.
- [x] (2026-03-25 00:20 +04) Re-read the current external-call implementation in `community/participant/src/main/scala/com/digitalasset/canton/participant/config/ExtensionServiceConfig.scala`, `community/participant/src/main/scala/com/digitalasset/canton/participant/extension/ExtensionServiceManager.scala`, `community/participant/src/main/scala/com/digitalasset/canton/participant/extension/HttpExtensionServiceClient.scala`, and `community/participant/src/main/scala/com/digitalasset/canton/participant/extension/ExtensionServiceExternalCallHandler.scala`.
- [x] (2026-03-25 00:21 +04) Re-read the current test harness in `community/app/src/test/scala/com/digitalasset/canton/integration/tests/externalcall/ExternalCallIntegrationTestBase.scala`, `community/app/src/test/scala/com/digitalasset/canton/integration/tests/externalcall/MockExternalCallServer.scala`, and `community/app/src/test/scala/com/digitalasset/canton/integration/tests/externalcall/RetryExternalCallIntegrationTest.scala`.
- [x] (2026-03-25 00:22 +04) Captured the settled design decisions that the rewritten spec must encode.
- [x] (2026-03-25 00:29 +04) Rewrote `community/participant/EXTERNAL_CALL_OAUTH_TECH_SPEC.md` completely so every section matches the settled decisions and current code seams.
- [x] (2026-03-25 00:31 +04) Reviewed the final spec for obsolete terms and contradictions, then updated this ExecPlan with outcomes and evidence.
- [x] (2026-03-25 00:42 +04) Revised the spec again so it uses canonical normative wording throughout instead of review-style design prose.
- [x] (2026-03-25 00:49 +04) Removed the remaining implementation choices from the spec by cementing exact client topology, token-refresh concurrency, token-endpoint transport semantics, trust-store defaults, and metrics policy.
- [x] (2026-03-25 00:53 +04) Clarified the `trust-collection-file` wording so the canonical spec states the exact present-versus-absent trust-store semantics instead of describing "custom trust material" informally.

## Surprises & Discoveries

- Observation: The startup validation API exists, but the participant startup path does not call it.
  Evidence: `community/participant/src/main/scala/com/digitalasset/canton/participant/ParticipantNode.scala` constructs `ExtensionServiceManager` and passes it into runtime services, but does not invoke `validateAllExtensions()`.

- Observation: The current validation method still performs a live HTTP request, so the current code does not match a "local-only startup validation" contract.
  Evidence: `community/participant/src/main/scala/com/digitalasset/canton/participant/extension/HttpExtensionServiceClient.scala` builds a `_health` request and sends it during `validateConfiguration()`.

- Observation: The current shared HTTP client already collapses TLS behavior across extensions when any one extension enables insecure TLS.
  Evidence: `community/participant/src/main/scala/com/digitalasset/canton/participant/extension/ExtensionServiceManager.scala` installs one insecure `SSLContext` on the manager-owned `HttpClient` if any extension has `tlsInsecure = true`.

- Observation: The existing runtime and tests treat resource-server `401` as terminal, so the OAuth-specific replay is a deliberate behavior change that the spec must describe explicitly.
  Evidence: `community/participant/src/main/scala/com/digitalasset/canton/participant/extension/HttpExtensionServiceClient.scala` maps `401` directly to an error, and `community/app/src/test/scala/com/digitalasset/canton/integration/tests/externalcall/RetryExternalCallIntegrationTest.scala` asserts "not retry on 401 Unauthorized".

## Decision Log

- Decision: Keep one OAuth-specific refresh-and-replay on resource-server `401`.
  Rationale: Short-lived access tokens still need expiry-based refresh, but production OAuth deployments also need a narrow recovery path when a token is rejected unexpectedly after the request has already been sent.
  Date/Author: 2026-03-25 / Codex with user decision

- Decision: Defer token-request `audience` from v1 and keep only optional `scope`.
  Rationale: `scope` is common enough to justify first-class support; token-request `audience` is provider-specific and expands config and tests without changing the core runtime model.
  Date/Author: 2026-03-25 / Codex with user decision

- Decision: Keep the resource-server configuration broadly flat instead of adopting nested `endpoint` config.
  Rationale: The current runtime, tests, and config readers are all centered on the flat `ExtensionServiceConfig` shape, so a broader config refactor would exceed the scope of an upstream-friendly OAuth feature.
  Date/Author: 2026-03-25 / Codex with user decision

- Decision: Move outbound HTTP client ownership to each `HttpExtensionServiceClient`.
  Rationale: OAuth adds a second outbound endpoint and potentially different trust material. Per-extension ownership keeps the seam narrow and avoids cross-extension TLS coupling.
  Date/Author: 2026-03-25 / Codex with user decision

- Decision: Clamp per-request timeout to the remaining total deadline, but do not require dynamic connect-timeout clamping in v1.
  Rationale: The current code already computes a hard total deadline and applies per-request timeouts, while connect timeout is fixed at client construction time. Tightening request timeout handling is useful; redesigning connect timeout behavior is not needed for the first spec revision.
  Date/Author: 2026-03-25 / Codex with user decision

- Decision: Model auth as a typed config variant instead of `auth.mode` plus an optional nested OAuth block.
  Rationale: A typed auth config removes invalid intermediate states and matches existing Canton config patterns for discriminated variants.
  Date/Author: 2026-03-25 / Codex with user decision

- Decision: Defer startup validation integration from the OAuth v1 spec.
  Rationale: Wiring validation into startup would be a separate participant boot-path feature. The OAuth spec should stay focused on runtime auth behavior and the config surface needed to support it.
  Date/Author: 2026-03-25 / Codex with user decision

- Decision: Reuse the existing external-call mock server for OAuth integration tests.
  Rationale: Extending the current server to handle token-endpoint paths keeps the test harness minimal and aligned with the existing integration suite.
  Date/Author: 2026-03-25 / Codex with user decision

- Decision: The spec text itself MUST be canonical and normative, not explanatory design prose.
  Rationale: The user requires the spec to be the authoritative implementation contract. That requires direct requirement statements (`MUST`, `MUST NOT`, `MAY`) instead of review framing such as "the goal is", "acceptable", or "current seam".
  Date/Author: 2026-03-25 / Codex with user decision

- Decision: The canonical spec MUST eliminate implementation choices rather than leaving them as `MAY` or "implementation-specific" behavior.
  Rationale: The user requires assertive and unconditional wording. The most likely canonical implementation uses one resource client for `auth.type = none`, two dedicated clients for `auth.type = oauth`, serialized per-extension token refresh, exact token-endpoint transport mappings, JVM-default trust stores when no trust collection is configured, and no new OAuth-specific metrics.
  Date/Author: 2026-03-25 / Codex with user decision

## Outcomes & Retrospective

The spec rewrite is complete. `community/participant/EXTERNAL_CALL_OAUTH_TECH_SPEC.md` now describes a narrower OAuth v1 design that aligns with the current participant extension code instead of describing a broader transport/config/startup redesign. The final document is also written as a canonical normative specification rather than a reviewed design memo. It keeps OAuth inside `HttpExtensionServiceClient`, keeps one OAuth-specific `401` refresh-and-replay, keeps the resource-server configuration broadly flat, uses a typed auth variant, defers startup validation integration, and reuses the existing `MockExternalCallServer` for integration tests.

The rewrite also removed the biggest mismatches from the old draft. The old nested `endpoint` resource-server example is gone, the separate startup-validation section is gone, and the token-request `audience` field is now explicitly out of scope. The final revisions removed design-review phrasing and then removed the remaining implementation choices by fixing exact behavior where the prior draft still allowed discretion.

This was a documentation-only change. No runtime code or tests were changed in this turn, so the proof of success is the rewritten spec content plus the search and diff evidence recorded below.

## Context and Orientation

The `external_call` feature lets Daml execution ask the participant to call an HTTP extension service. The participant-facing boundary is `community/participant/src/main/scala/com/digitalasset/canton/participant/extension/ExtensionServiceExternalCallHandler.scala`, which adapts Canton extension errors to the Daml engine. `community/participant/src/main/scala/com/digitalasset/canton/participant/extension/ExtensionServiceManager.scala` owns the map of configured extensions and currently constructs one `HttpExtensionServiceClient` per extension. `community/participant/src/main/scala/com/digitalasset/canton/participant/extension/HttpExtensionServiceClient.scala` is the real runtime seam: it constructs the resource-server URI, injects the current bearer token, runs the outer retry loop, and classifies HTTP failures.

Configuration currently lives in `community/participant/src/main/scala/com/digitalasset/canton/participant/config/ExtensionServiceConfig.scala`. The resource server is configured with flat fields such as `host`, `port`, `useTls`, `tlsInsecure`, `requestTimeout`, and retry settings. The same file also still exposes `jwt` and `jwtFile` for the current static bearer-token model. The config reader in `community/app-base/src/main/scala/com/digitalasset/canton/config/CantonConfig.scala` auto-derives this shape directly, so changing the shape of `ExtensionServiceConfig` has a broad impact.

The integration tests for `external_call` already have a working HTTP fixture. `community/app/src/test/scala/com/digitalasset/canton/integration/tests/externalcall/ExternalCallIntegrationTestBase.scala` starts the mock server and injects `ExtensionServiceConfig` into participant config. `community/app/src/test/scala/com/digitalasset/canton/integration/tests/externalcall/MockExternalCallServer.scala` handles requests, tracks call counts, and allows per-function handlers. `community/app/src/test/scala/com/digitalasset/canton/integration/tests/externalcall/RetryExternalCallIntegrationTest.scala` currently asserts that `401` is terminal, which is important context because the OAuth spec intentionally changes that behavior only for the OAuth path.

The current spec file, `community/participant/EXTERNAL_CALL_OAUTH_TECH_SPEC.md`, overreaches in three ways. It introduces a nested config model that the code does not have, it describes startup validation behavior that is not wired into participant startup, and it leaves HTTP/TLS ownership ambiguous even though the current shared-client design is already problematic for per-extension TLS. The rewrite must fix those mismatches while preserving the useful parts of the original spec: keep OAuth inside the `HttpExtensionServiceClient` seam, keep one auth-local `401` replay, reuse the existing retry/error boundary, and keep tests scoped to the current harness.

## Plan of Work

Rewrite `community/participant/EXTERNAL_CALL_OAUTH_TECH_SPEC.md` from top to bottom rather than editing it incrementally. The new document should begin by describing the current code seam plainly: `ParticipantNode` constructs an `ExtensionServiceManager`, the manager constructs one `HttpExtensionServiceClient` per extension, and the client owns request orchestration, token acquisition, retry logic, and error mapping. The runtime design section must then state that each `HttpExtensionServiceClient` owns the HTTP client state for its extension, including separate token-endpoint and resource-server clients when TLS settings differ.

Replace the request-execution section so it describes the actual v1 flow. The new text must say that one absolute deadline is computed from `max-total-timeout`, that request timeouts are clamped to remaining budget, that no token or resource call starts when no positive budget remains, and that one outer attempt may include one auth-local `401` replay. The wording must avoid any requirement to dynamically clamp connect timeout on every attempt.

Replace the token-acquisition section so it is explicit about client-credentials flow, `private_key_jwt`, opaque bearer tokens, optional `scope`, and no token-request `audience`. The caching rules should say that expiry-based refresh is required, proactive refresh is out of scope, and a resource-server `401` invalidates the cached token for one replay attempt. Keep the error-handling story narrow: token-endpoint failures consume the same outer retry budget as resource-server failures, malformed token responses map to `502`, and local signing or key-loading failures map to `500`.

Replace the configuration section so it matches the chosen shape. The text must say that the resource-server config stays broadly flat on `ExtensionServiceConfig`, that the legacy `jwt` and `jwtFile` fields are replaced by a typed `auth` config with `none` and `oauth` variants, and that the OAuth variant contains token-endpoint settings, client ID, key material, optional `scope`, and any key identifier. The example config must use the flat resource-server shape rather than a nested `endpoint` block.

Delete the startup-validation section entirely from the spec. The rewritten document can still mention the current validation helpers as existing code if needed, but it must not promise startup gating or local-only validation as part of OAuth v1. Rewrite the testing section so it says that unit tests cover token acquisition, expiry-driven refresh, cache reuse, and `401` replay, and that integration tests extend the existing `MockExternalCallServer` to serve both token-endpoint and resource-server paths.

## Concrete Steps

Work from the repository root, `/Users/al/Projects/angelo/zenith/full-stack/canton`.

1. Reconfirm the file and code anchors before editing.

       nl -ba community/participant/EXTERNAL_CALL_OAUTH_TECH_SPEC.md | sed -n '1,280p'
       nl -ba community/participant/src/main/scala/com/digitalasset/canton/participant/config/ExtensionServiceConfig.scala | sed -n '1,120p'
       nl -ba community/participant/src/main/scala/com/digitalasset/canton/participant/extension/ExtensionServiceManager.scala | sed -n '1,180p'
       nl -ba community/participant/src/main/scala/com/digitalasset/canton/participant/extension/HttpExtensionServiceClient.scala | sed -n '1,360p'

2. Rewrite `community/participant/EXTERNAL_CALL_OAUTH_TECH_SPEC.md` completely so every section is internally consistent.

3. Review the resulting document for leftover obsolete terms from the old design.

       rg -n "auth.mode|token-request audience|Startup Validation|endpoint =|validation-mode" community/participant/EXTERNAL_CALL_OAUTH_TECH_SPEC.md

   The expected result is no match for obsolete terms that were intentionally removed, or only matches in places where the new spec is explicitly contrasting old and new behavior.

4. Review the diff for the spec and this ExecPlan.

       git diff -- .agent/plans/2026-03-25-0022-rewrite-external-call-oauth-spec.md community/participant/EXTERNAL_CALL_OAUTH_TECH_SPEC.md

## Validation and Acceptance

Acceptance is documentation behavior, not runtime behavior. The rewrite is complete when a novice can read the new `community/participant/EXTERNAL_CALL_OAUTH_TECH_SPEC.md` and implement OAuth without needing to know unstated repository context, and when the text no longer promises behavior the current codebase does not have.

Use these checks:

- Open the rewritten spec and verify that it explains the current code seam with concrete file paths and roles.
- Confirm that the configuration example keeps the resource-server fields flat and uses a typed auth variant.
- Confirm that the runtime design includes one OAuth-specific `401` replay and expiry-based refresh.
- Confirm that the spec does not promise startup gating or local-only startup validation.
- Confirm that the testing section explicitly reuses `MockExternalCallServer`.

The `git diff` should show a complete replacement of the old spec with the new design. The obsolete-term search should not show `auth.mode`, a token-request `audience` field, or a `Startup Validation` section in the new document.

## Idempotence and Recovery

This work is safe to repeat. Re-running the search and diff commands only inspects the current state. If a rewrite introduces contradictions, open the file again, replace the inconsistent section, and re-run the review commands. No generated files or external systems are involved.

## Artifacts and Notes

Key evidence to preserve in the rewrite:

    The runtime seam is still:
      ParticipantNode -> ExtensionServiceManager -> HttpExtensionServiceClient -> external HTTP service

    The chosen v1 constraints are:
      - one OAuth-specific refresh-and-replay on resource-server 401
      - optional scope, but no token-request audience
      - flat resource-server config
      - per-extension HTTP client ownership
      - request-timeout clamping only
      - typed auth config
      - startup validation deferred
      - existing mock external-call server extended for OAuth tests

Validation evidence from the completed rewrite:

    rg -n '^## Startup Validation$|validate-extensions-on-startup|fail-on-extension-validation-error' community/participant/EXTERNAL_CALL_OAUTH_TECH_SPEC.md -S
    => no matches

    rg -n '^\s*endpoint = \{$|auth\.mode' community/participant/EXTERNAL_CALL_OAUTH_TECH_SPEC.md -S
    => one deliberate contrast-only match:
       282: OAuth v1 uses a typed auth variant rather than `auth.mode` plus an optional nested OAuth block.

    rg -n '\b(goal|acceptable|continues|should|conceptually|intentionally)\b' community/participant/EXTERNAL_CALL_OAUTH_TECH_SPEC.md -S
    => only the RFC-style keyword convention line contains "SHOULD"

    rg -n '\bMAY\b|when possible|implementation-specific|broadly|optional ' community/participant/EXTERNAL_CALL_OAUTH_TECH_SPEC.md -S
    => no matches

    TLS trust-store semantics:
      - if `trust-collection-file` is present, the implementation MUST use it
      - if `trust-collection-file` is absent, the implementation MUST use the JVM default trust store

## Interfaces and Dependencies

The rewritten spec must refer to the following repository interfaces and preserve their current responsibilities unless it explicitly proposes a change:

- `community/participant/src/main/scala/com/digitalasset/canton/participant/extension/ExtensionServiceExternalCallHandler.scala`
  This remains the thin boundary mapper that exposes only `statusCode`, `message`, and `requestId` to the Daml engine.

- `community/participant/src/main/scala/com/digitalasset/canton/participant/extension/ExtensionServiceManager.scala`
  This remains responsible for creating one `HttpExtensionServiceClient` per extension and dispatching calls by extension ID. The new spec must not ask it to become a general auth subsystem.

- `community/participant/src/main/scala/com/digitalasset/canton/participant/extension/HttpExtensionServiceClient.scala`
  This remains the implementation seam for request orchestration, token acquisition, retry handling, the auth-local `401` replay, and error mapping.

- `community/participant/src/main/scala/com/digitalasset/canton/participant/config/ExtensionServiceConfig.scala`
  This remains the top-level per-extension config type. The spec may describe replacing `jwt` and `jwtFile` with a typed `auth` variant, but it must not claim a broader transport abstraction than the code needs.

- `community/app/src/test/scala/com/digitalasset/canton/integration/tests/externalcall/MockExternalCallServer.scala`
  This remains the integration-test HTTP fixture that OAuth tests should extend rather than replace.

Change note: This ExecPlan was created to guide a full rewrite of `community/participant/EXTERNAL_CALL_OAUTH_TECH_SPEC.md` after a code-grounded review showed that the existing spec mixed correct runtime goals with config and startup behavior that the current codebase does not support. The plan resolves those mismatches up front so the new spec can be written once and read independently.

Revision note: After completing the rewrite, this plan was updated to mark all work complete, record the validation evidence, and summarize the documentation outcome so a future reader can restart from this file alone.

Revision note: After the user required canonical wording, this plan was updated again to record the normative-language revision and the final validation checks that distinguish requirement text from explanatory prose.

Revision note: After the user required unconditional canonicality, this plan was updated again to record the final pass that removed the remaining implementation choices from the spec.

Revision note: After the user called out the ambiguous "custom trust material" wording, this plan was updated again to record the explicit present-versus-absent semantics for `trust-collection-file`.
