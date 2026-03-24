# External Call OAuth Requirements

## Overview

This document defines the requirements for replacing the current static bearer token mechanism used by `external_call` with OAuth-integrated service-to-service authentication.

The goal is to make external call authentication production-ready while staying aligned with existing Canton auth, JWT, TLS, and token lifecycle patterns. The design must reuse existing infrastructure wherever it fits cleanly and must not introduce new auth patterns unless there is no existing equivalent in the codebase.

## Current Problem

The current external call implementation allows a literal bearer token to be configured directly or loaded from a file and then attached to outbound HTTP requests.

This is insufficient for production use because it does not provide:

- OAuth integration
- token acquisition from an authorization server
- expiry-aware token lifecycle management
- proactive renewal or refresh
- invalidation and rotation handling
- audience and scope management
- strong client authentication

It is also out of line with the rest of the codebase, which already has established patterns for JWT validation, issuer and JWKS handling, audience and scope enforcement, TLS-based security, and managed token lifecycles.

## Goals

- Replace the static bearer token approach for authenticated external calls with OAuth-based service-to-service authentication.
- Align configuration and runtime behavior with existing Canton auth and JWT patterns.
- Reuse existing lifecycle-management patterns for token caching, refresh, invalidation, retry, and backoff.
- Support production-grade machine authentication without inventing a parallel auth model specific to external calls.
- Establish a single canonical production path: OAuth with JWT-assertion-based client authentication over standard TLS.
- Keep the external call business protocol unchanged unless an auth requirement makes a change unavoidable.

## Non-Goals

- Building a generic OAuth framework for all Canton components.
- Adding interactive OAuth flows such as authorization code or device flow.
- Redesigning the external call payload protocol or function contract.
- Replacing the existing sequencer authentication protocol.
- Introducing extension-service-specific business authorization semantics into Canton.
- Supporting sender-constrained mechanisms such as mTLS-bound access tokens.

## Reuse-First Design Principles

The implementation must follow these principles:

1. Reuse existing auth semantics before adding new ones.
2. Reuse existing config vocabulary before adding new field names.
3. Reuse existing lifecycle-management patterns before adding new token-refresh logic.
4. Reuse existing crypto and TLS handling before introducing new key or certificate handling code.
5. Keep outbound auth isolated behind an abstraction so `HttpExtensionServiceClient` does not own token lifecycle logic directly.

## Existing Infrastructure And Patterns To Reuse

The requirements below assume reuse of the following existing codebase patterns where applicable:

- `AuthServiceConfig` for JWT-related config vocabulary and semantics such as `targetAudience`, `targetScope`, certificate- and JWKS-based trust configuration, and max token lifetime.
- `AuthServiceJWT` and `AuthServiceJWTPayload` for canonical JWT claim handling and audience/scope semantics.
- `CachedJwtVerifierLoader` for JWKS retrieval and caching when verifier loading is needed.
- declarative identity-provider configuration semantics already used by the ledger API: issuer, JWKS URL, and audience.
- `AuthenticationTokenManagerConfig` and the lifecycle semantics embodied by `AuthenticationTokenProvider` and `AuthenticationTokenManager` for token acquisition, caching, pre-expiry refresh, retry/backoff configuration shape, and invalidation patterns, while defining an OAuth-specific HTTP retryability matrix instead of reusing the gRPC exception policy.
- existing TLS client/server config patterns for certificate handling and trust configuration.
- existing JWT signing and key loading helpers such as `JwtSigner` and `KeyUtils`.
- existing Canton crypto usage patterns for private-key-backed JWT assertions where required.

These requirements do not assume that the existing inbound JWT verifier stack is directly reusable for outbound token acquisition, or that sequencer-specific token acquisition classes are directly reusable for OAuth. Where reuse is only conceptual rather than literal, the implementation must reuse semantics and operational patterns rather than force-fit the exact existing classes or storage models.

## High-Level Requirements

### R1. OAuth-Based Service Authentication

Authenticated external calls must use OAuth-compatible service-to-service authentication rather than a statically configured bearer token.

At minimum:

- the participant must be able to obtain an access token through an OAuth service-to-service flow suitable for machine clients
- the access token must be attached to outbound external call requests
- the mechanism must support provider-required audience and scope configuration
- the token response must provide `token_type = Bearer`, matched case-insensitively; any other token type must be rejected

### R2. No Static Bearer Token As The Primary Auth Model

The current `jwt` / `jwtFile` mechanism must not remain the primary or recommended auth path for external calls.

If backward compatibility requires a temporary transition period:

- static-token configuration must be explicitly deprecated
- it must be mutually exclusive with the new OAuth configuration
- it must not be the documented production path

### R3. Explicit Auth Modes

External call authentication must be explicit and mode-based.

At a minimum, the configuration model must distinguish:

- no authentication
- OAuth-based authentication

This is required so development and test environments can remain unauthenticated without conflating that case with production authentication.

### R4. Reuse Existing JWT Terminology

Where external call auth configuration needs JWT- or OAuth-related concepts already used elsewhere in Canton, it should use existing Canton terminology and semantics rather than introducing synonyms:

- `audience` / `targetAudience`
- `scope` / `targetScope`
- token lifetime
- timestamp leeway

The implementation must not introduce alternative names for the same concepts unless forced by an external RFC field.

This requirement is about vocabulary and semantics, not about reusing the inbound identity-provider store or inbound JWT verifier stack directly for outbound OAuth.

### R5. Reuse Existing Token Lifecycle Pattern

The external call auth flow must use the same lifecycle pattern already used by sequencer authentication:

- lazy first acquisition
- shared in-flight acquisition for concurrent callers
- cached current token
- proactive refresh before expiry
- invalidation when the token is rejected
- retry and backoff around token acquisition failures

Any new configuration for refresh and retry should prefer reusing `AuthenticationTokenManagerConfig` where it fits cleanly, or otherwise use a shape intentionally derived from it, instead of inventing a separate lifecycle config model.

This requirement does not imply that the sequencer-specific token provider or gRPC authentication machinery should be reused directly for OAuth token acquisition.

### R6. Reuse Existing Retry Boundaries

The external call client already has request retry logic. OAuth integration must compose with that existing retry path rather than create a second, conflicting request-retry loop.

In practice:

- token acquisition retries belong to the auth/token-manager layer
- external call transport retries remain in the HTTP client layer
- token rejection must invalidate cached auth state before the next retry attempt
- OAuth token acquisition must use an explicit HTTP-specific retryability matrix; it must not inherit the gRPC exception retry policy from sequencer authentication implicitly
- fatal auth failures must remain terminal for the outer business-request retry loop even when their engine-facing boundary mapping uses an HTTP status code that is retryable in ordinary transport handling
- token acquisition and refresh must fit within the external call timeout model; they must not introduce an unbounded second budget that can silently overrun the call's configured deadlines
- foreground connect time and request time must both be composed with the remaining outer external-call deadline; neither may overrun `max-total-timeout`
- if a distinct auth sub-budget is introduced, it must be explicitly bounded and composed with the existing external call timeouts

## Client Authentication Requirements

### R7. Production-Grade Client Authentication

OAuth token acquisition must support a production-grade client authentication method that aligns with patterns already present in the codebase.

The production client-authentication method for this design is:

- JWT assertion based client authentication using existing JWT signing and key-loading infrastructure

The design must not require a long-lived shared secret in plain configuration as the only production authentication method.

### R8. No New Ad Hoc Key Handling

If JWT assertions are required, private key loading, signing, and rotation must reuse existing key and crypto handling patterns.

The implementation must not introduce a separate, extension-specific key format or signing stack unless an external standard requires it and existing utilities cannot support it.

### R9. Sender-Constrained Mechanisms Are Out Of Scope

The final design does not need to support sender-constrained mechanisms such as:

- mTLS-bound access tokens
- other proof-of-possession style mechanisms beyond JWT assertion based client authentication

The accepted tradeoff is that issued bearer access tokens can be replayed until expiry if they are exfiltrated.

This is an explicit scope decision in favor of a simpler and more canonical production auth contract.

## Token Lifecycle Requirements

### R10. Expiry-Aware Caching

Access tokens must be cached together with expiry information when the provider supplies it.

The token manager must treat tokens as unusable when the current time reaches the configured refresh-before-expiry cutoff, following the same operational model as the existing authentication token manager.

### R11. Renewal Strategy

The implementation must support renewing token state before expiry.

This renewal may be implemented by:

- acquiring a new token before expiry
- refreshing a token when the provider supports refresh tokens

The abstraction must not assume refresh-token support is always available. Reacquisition must be a first-class path.

### R12. Invalidation On Rejection

If an external service rejects the current access token with an authentication failure, the cached token must be invalidated so that the same call attempt or the next attempt fetches new credentials.

This requirement is directly aligned with the invalidation behavior in the existing sequencer client authentication stack.

The implementation must define a precise HTTP invalidation policy rather than relying on a generic notion of rejection. Invalidation must be driven by explicit auth-failure signals, such as a provider-compatible `401 Unauthorized` and related authentication challenge semantics, and must not be triggered by ordinary transport failures or application-level errors.

If the invalidation happens after a request has already been sent, the invalidation rule must be token-conditional so that a stale rejection does not evict a newer token obtained concurrently.

### R13. Shared Concurrent Acquisition

When multiple concurrent external calls need a token for the same auth context, they must share the same in-flight token acquisition instead of stampeding the authorization server.

This should follow the same single-refresh / shared-promise pattern used by `AuthenticationTokenManager`.

### R14. Rotation Compatibility

The implementation must support rotation of:

- signing keys used for client authentication
- TLS certificates and trust material used for the token endpoint or resource server
- authorization-server keys relevant to any local verification or discovery logic

Where local verification or trust material lookup is needed, existing JWKS and certificate-loading patterns must be reused.

This requirement does not imply that every material type must hot-reload in-process. Participant-restart pickup is acceptable for TLS trust-material rotation as long as the runtime behavior is documented explicitly.

## Configuration Requirements

### R15. Auth Config Must Be Nested Under Extension Service Config

External call auth must be configured as part of the extension service definition rather than through ad hoc global state.

The auth configuration should be structured so that:

- an extension can be unauthenticated
- different extensions can use different OAuth providers, client identities, audiences, or scopes
- the HTTP client receives a resolved auth strategy rather than raw auth fields

### R16. Configuration Must Preserve Existing TLS Structure

External service transport security and OAuth client authentication must align with existing TLS config structure and naming where possible.

This applies both to:

- the resource server connection used for the external call itself
- the token endpoint connection used for OAuth

### R17. Secret Material Must Use Existing Confidential Config Handling

Any secret-bearing configuration that remains necessary must use existing confidential-config handling patterns and must be clearly separated from non-secret metadata such as audience and scope.

### R18. Avoid Duplicating Identity Provider Semantics

Audience and scope semantics in outbound OAuth must match the identity-provider and auth-service patterns already present in the codebase.

The implementation must not force-fit outbound OAuth into the inbound identity-provider store. Reuse of semantics is required; reuse of the exact storage model or verifier pipeline is not part of the final design.

### R19. Validation Of Misconfiguration

Startup and config validation must reject clearly invalid combinations such as:

- static token config combined with OAuth config
- OAuth config missing required token endpoint or client-assertion information
- token-endpoint path values that are not absolute paths or that encode query/fragment components
- audience and scope combinations that cannot be satisfied
- private key or certificate references that cannot be loaded

The token-endpoint config must derive one canonical HTTPS URI that is used both as the actual token request target and as the `private_key_jwt` assertion audience. The design must not introduce a second independently configured client-assertion audience field.

## Runtime Behavior Requirements

### R20. External Call Protocol Stability

OAuth integration must not change the functional external call protocol unless necessary.

The following must remain stable unless a change is explicitly justified:

- endpoint shape for the business request
- existing Daml external call headers
- request and response body semantics
- submission vs validation mode semantics

### R21. Determinism Preservation

Auth metadata must not become part of the business semantics of the external call.

In particular:

- submission and validation executions must remain equivalent from the external service's business perspective
- token renewal, timestamps, or JWT assertion material must not be allowed to affect the external call result in a way that breaks determinism

This is a hard requirement because external calls are re-executed during validation.

### R22. Auth-Aware Configuration Validation

`validateConfiguration()` must evolve from a basic connectivity check to an auth-aware validation flow for authenticated extensions.

Validation should cover, as applicable:

- auth configuration completeness
- ability to load required keys or certificates
- reachability of the token endpoint when validation is enabled
- acquisition of a token when safe and appropriate
- reachability of the extension service under the configured auth mode

Validation must avoid invoking business functions.

Auth-aware validation must not implicitly require successful contact with an authorization server during startup in every deployment mode. The design must distinguish between:

- local configuration validation
- best-effort remote auth validation
- fail-closed startup behavior when explicitly configured

Failure rule:

- in every mode except `off`, local validation failures are fatal to startup
- local validation failures include malformed config, mutually inconsistent config, unreadable private keys, unreadable certificate or trust files, and invalid TLS material
- only remote validation failures are tolerated in best-effort remote mode

Mixed-set and aggregation rule:

- one global validation mode applies to the full configured extension set
- unauthenticated extensions and OAuth extensions are both validated under that mode, with per-extension checks determined by `auth.mode`
- validation must not fail fast on the first broken extension
- startup validation must evaluate the full extension set, aggregate errors deterministically by extension id, and then apply the mode's startup success/failure rule to that aggregated result
- the validation API must expose a structured per-extension report that distinguishes local errors, remote errors, and non-fatal remote warnings

The default behavior should avoid making the participant startup path more brittle than the current extension validation model unless the operator explicitly opts into stricter startup enforcement.

### R23. Clear Failure Classification

Failures must distinguish between:

- token acquisition failures
- token validation or token rejection failures
- transport failures to the external service
- application-level failures returned by the external service

OAuth integration must not collapse all of these into the same generic external call error path.

The internal auth layer must use a structured auth-failure envelope that carries failure class, message, and the optional participant-generated token-endpoint request identifier when a token-endpoint HTTP interaction had already started.

At the engine-facing `ExternalCallError` boundary, those internal classes must map back deterministically to `statusCode`, `message`, and `requestId`. HTTP status codes from the failing upstream interaction must be preserved where available; synthesized boundary statuses must be used consistently for timeout, connect/I/O, malformed token-response, and participant-side auth-material failures. When multiple HTTP interactions occur inside one outer business attempt, `requestId` must use a deterministic precedence rule based on the interaction that produced the final returned failure.

## Security Requirements

### R24. TLS By Default

OAuth-enabled external calls must use TLS for both the token endpoint and the resource server in the supported production contract.

Plaintext transport must be rejected for OAuth mode. Any retained insecure or trust-all hook must remain clearly marked as development-only implementation scaffolding and must not be part of the supported production config contract.

### R25. Least-Privilege Tokens

OAuth integration must support least-privilege token requests through explicit audience and scope configuration.

The design must not assume a single unscoped token is acceptable across all extensions.

### R26. No Secret Logging

The implementation must not log:

- access tokens
- refresh tokens
- client assertions
- client secrets
- private key material

Logs may include safe metadata such as:

- extension identifier
- audience
- scope
- request identifiers
- auth mode
- failure class

### R27. Max Token Lifetime Controls

Where the code needs to reason about locally issued or externally received JWTs, existing max token lifetime and timestamp leeway patterns should be reused so that external call auth does not become a special case with incompatible lifetime rules.

## Observability Requirements

### R28. Structured Logging

OAuth integration must emit structured logs around:

- token acquisition attempts
- token refresh
- token invalidation
- auth failures
- external call failures after auth has succeeded

These logs must follow existing Canton logging style and include enough context to distinguish auth problems from business-call problems.

### R29. Metrics

Where practical, the implementation should expose metrics for:

- token acquisition success and failure counts
- token refresh count
- token invalidation count
- cache hit and miss behavior
- auth-related latency

Existing cache and request-metric patterns should be reused where they fit.

## Testing Requirements

### R30. Unit Test Coverage

Unit tests must cover at least:

- config validation and mutual exclusivity rules
- token acquisition success and failure
- shared concurrent acquisition
- proactive refresh before expiry
- invalidation after authentication failure
- audience and scope propagation
- key or certificate loading failures

### R31. Integration Test Coverage

Integration tests must cover at least:

- unauthenticated extension calls still working when configured
- OAuth-protected extension calls succeeding end to end
- expired token renewal
- auth rejection causing token invalidation and subsequent recovery
- submission and validation both working with the same auth model
- extension service key or token rotation scenarios

### R32. Backward-Compatibility Tests

If static token configuration is temporarily retained for migration, tests must assert:

- deprecation behavior is explicit
- new OAuth configuration takes precedence only when explicitly selected
- invalid mixed configuration is rejected

## Migration Requirements

### R33. Smooth Migration Path

The change must include a clear migration path from static bearer token configuration to OAuth configuration.

This migration path must specify:

- how existing `jwt` / `jwtFile` users move to the new config
- which configs are deprecated
- whether a transitional compatibility window exists
- what the documented production path is after the migration

### R34. No Silent Behavior Change

Existing installations must not silently switch auth behavior based on partial config.

If OAuth is enabled, it must be because the configuration explicitly selected it.

## Acceptance Criteria

The requirements in this document are satisfied when all of the following are true:

- authenticated external calls no longer depend on a statically configured bearer token
- the new auth flow uses OAuth-compatible service authentication
- token lifecycle management follows the same operational model already used elsewhere in the codebase
- JWT, audience, scope, TLS, and key-handling semantics align with existing Canton patterns
- the documented production path is OAuth with JWT-assertion-based client authentication over TLS
- the implementation introduces no extension-specific auth model unless required by an external protocol constraint
- the resulting design is documented and testable without special-case operational knowledge
