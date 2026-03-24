# External Call OAuth Tech Spec

## Status

Draft for iterative refinement.

This document turns the requirements into a codebase-grounded design for the current participant implementation.

## Scope and Final-State Decisions

Add OAuth-based service-to-service authentication to participant external calls without changing the Daml external-call business protocol.

This spec is grounded in the current participant external-call, auth, and validation implementation.

The following choices are assumed throughout this draft:

- The canonical production contract is `auth.mode = oauth` with `private_key_jwt` client authentication over TLS.
- OAuth changes operational transport and authentication behavior only. It must not change Daml-level business semantics.
- OAuth configuration remains per extension.
- Outbound OAuth may reuse audience and scope semantics already present elsewhere in Canton auth, but it does not reference named identity-provider definitions directly.
- This design assumes there are no existing external-call users to preserve, so the final config may replace the current static-token shape directly. No compatibility alias layer is required.
- Sender-constrained mechanisms such as mTLS are out of scope.

## Current Implementation Constraints

### External-call path today

The current external-call stack is:

1. `ParticipantNode` creates one `ExtensionServiceManager` when `parameters.engine.extensions` is non-empty.
2. `ExtensionServiceManager` creates one `HttpExtensionServiceClient` per configured extension.
3. `ExtensionServiceExternalCallHandler` forwards Daml engine calls to the manager.
4. `HttpExtensionServiceClient` builds the HTTP request, injects the current auth header, performs the transport call, classifies the response, and owns request retry logic.

### Constraints that drive the refactor

`ExtensionServiceConfig` currently mixes three concerns in one case class:

- resource-server transport: `host`, `port`, `useTls`, `tlsInsecure`
- auth material: `jwt`, `jwtFile`
- transport lifecycle: timeouts and retry settings

`HttpExtensionServiceClient` currently:

- lazily reads a literal token from `jwt` or `jwtFile`
- injects `Authorization: Bearer <token>` on every request
- treats `401` as terminal
- has no token expiry, refresh, invalidation, or concurrent acquisition control

`HttpExtensionServiceClient.callWithRetry` is the correct outer retry boundary and must remain so:

- retryable: `408`, `429`, `500`, `502`, `503`, `504`
- non-retryable: `400`, `401`, `403`, `404`
- bounded by `maxTotalTimeout`

Existing Canton pieces are reusable in pattern, not in exact type:

- JWT audience and scope semantics in `AuthServiceConfig` and `AuthServiceJWT`
- JWKS retrieval and caching in `CachedJwtVerifierLoader`
- token lifecycle semantics and retry/backoff behavior in `AuthenticationTokenManager`
- JWT signing helpers in `com.daml.jwt.JwtSigner` and `KeyUtils`
- TLS trust configuration semantics in existing `TlsClientConfig` usage

`AuthenticationTokenManager` cannot be reused literally for OAuth access tokens because it is typed around sequencer `AuthenticationToken`, which is a fixed-length binary token with a gRPC-oriented error surface. The lifecycle pattern is reusable; the concrete types are not.

## Target Runtime Architecture

### Auth boundary

Add a dedicated participant extension auth package and move all auth-specific behavior behind a new provider boundary. The auth layer is responsible for:

- resolving auth config into a concrete strategy
- decorating outbound business requests with auth material
- acquiring, caching, refreshing, and invalidating auth state
- validating local auth configuration and remote auth reachability
- owning auth-side lifecycle resources so they can be closed with the extension manager

Validation reporting remains at the extension-manager boundary rather than inside auth internals.

Proposed logical types:

- `ExternalCallAuthConfig`
- `ExternalCallAuthProvider`
- `NoAuthProvider`
- `OAuthExternalCallAuthProvider`
- `OAuthAccessTokenManager`
- `OAuthTokenClient`
- `PrivateKeyJwtConfig`

Concrete provider contract:

```scala
prepareRequest(
  deadline: CantonTimestamp
)(implicit tc: TraceContext)
  : FutureUnlessShutdown[Either[ExternalCallAuthFailure, PreparedAuth]]

handleResponse(
  responseContext: AuthResponseContext,
  preparedAuth: PreparedAuth,
  deadline: CantonTimestamp
)(implicit tc: TraceContext)
  : FutureUnlessShutdown[AuthResponseDecision]
```

Supporting types:

```scala
final case class PreparedAuth(
  authorizationHeader: Option[String],
  tokenUsed: Option[String],
  tokenEndpointRequestId: Option[String],
)

final case class AuthResponseContext(
  statusCode: Int,
  resourceRequestId: String,
  wwwAuthenticate: Option[String],
)

sealed trait AuthResponseDecision
case object NoReplay extends AuthResponseDecision
final case class ReplayOnceWithFreshAuth(nextPreparedAuth: PreparedAuth) extends AuthResponseDecision
final case class FailAuth(authFailure: ExternalCallAuthFailure) extends AuthResponseDecision
```

Contract rules:

- `prepareRequest` performs any foreground token acquisition needed for the current outer attempt.
- `prepareRequest` receives the absolute outer deadline and must clamp token-endpoint work to that deadline.
- `PreparedAuth.tokenUsed` is the exact token attached to the outgoing request and is the value used for token-conditional invalidation.
- `PreparedAuth.tokenEndpointRequestId` records the last participant-generated token-endpoint request id involved in preparing auth for the current outer attempt.
- `AuthResponseContext` carries only the metadata the auth layer is allowed to inspect: status code, participant-generated resource request id, and the first `WWW-Authenticate` header when present.
- Request ids in this design are participant-generated outbound correlation ids. Servers may echo them, but the protocol does not depend on a response-header request-id contract.
- `handleResponse` may request at most one auth-local replay inside an outer attempt and never advances the outer retry counter.

### `HttpExtensionServiceClient` after refactor

`HttpExtensionServiceClient` keeps:

- endpoint shape `/api/v1/external-call`
- `X-Daml-External-*` headers
- participant-generated request ids
- resource-server HTTP transport
- outer retry budget and response classification

It stops owning:

- token file loading
- token caching and refresh
- token invalidation policy
- token-endpoint request construction

Each outer attempt becomes:

1. compute remaining `maxTotalTimeout`
2. ask the auth provider to prepare auth using that deadline
3. send the resource request
4. let the auth provider inspect `401` responses
5. feed the attempt result back into the existing outer retry loop

Because both auth-provider calls are `FutureUnlessShutdown`, the outer retry loop must be rewritten around async `FutureUnlessShutdown` composition. Retry delays must not use `Threading.sleep`. The actual HTTP send may remain blocking internally or move to `sendAsync`, but the attempt orchestration itself must be async.

### OAuth token management

Add an OAuth-specific access-token manager that reuses the `AuthenticationTokenManager` lifecycle model and configuration semantics:

- lazy first acquisition
- one shared in-flight acquisition or refresh
- cached token with expiry
- background refresh before expiry
- explicit invalidation
- retry/backoff during acquisition

Concrete token type:

- `OAuthAccessTokenWithExpiry(accessToken: String, expiresAt: CantonTimestamp, tokenType: String)`

`OAuthAccessTokenManager` uses `AuthenticationTokenManagerConfig` directly. There is no external-call-specific lifecycle config.

Lifecycle ownership:

- `ExtensionServiceManager` owns auth providers and passes a `Clock` plus `isClosing` signal into `OAuthAccessTokenManager`
- `ExtensionServiceManager.onClosed()` closes auth providers so background refresh stops during shutdown
- `ParticipantNode` passes its existing clock into the manager
- `ParticipantNode` must register the extension manager as a closeable so `onClosed()` is guaranteed to run

Foreground and background acquisition rules:

- foreground acquisition is driven by a business request and uses the remaining outer deadline from `HttpExtensionServiceClient`
- background refresh is not tied to a business request; it is bounded only by token-manager retry settings and per-attempt HTTP timeouts
- failed background refresh clears the cached token, matching `AuthenticationTokenManager` semantics
- the next business request then performs foreground acquisition

Shared in-flight acquisition rules:

- `OAuthAccessTokenManager` preserves one shared in-flight fetch, whether it was started in the foreground or background
- a second caller waits on that shared future rather than starting a second token-endpoint request
- each waiting business request still enforces its own outer deadline while waiting
- if one waiting caller times out, it fails locally without cancelling the shared fetch
- a shared fetch that later succeeds populates the cache for later callers

### Token-endpoint client and retry policy

`OAuthTokenClient` is a dedicated HTTP client for the token endpoint. It:

- talks only to the configured token endpoint
- uses its own TLS settings
- generates a participant-local request id for each token-endpoint interaction and sends it in the configured request-id header
- receives an absolute deadline for foreground acquisition
- returns `OAuthAccessTokenWithExpiry`
- never logs secret-bearing inputs or outputs

The token-endpoint retryability matrix is specific to this design and is not inherited from the gRPC exception policy used elsewhere.

Retryable token-endpoint failures:

- HTTP `408`
- HTTP `429`
- HTTP `500`, `502`, `503`, `504`
- connect timeout before an HTTP response
- request timeout before an HTTP response
- transient connect or I/O failure before an HTTP response

Fatal token-endpoint failures:

- HTTP `400`, `401`, `403`, `404`
- any other `4xx` not listed above
- TLS trust or certificate failure
- TLS hostname-verification failure
- malformed token response
- unsupported `token_type`
- client-assertion signing failure
- local auth-material or key-loading failure

Retry timing rules:

- `429` honors `Retry-After` when present, capped by token-manager retry settings
- during foreground acquisition, every retry attempt and delay must fit within the caller's remaining outer deadline
- during background refresh, the same retryability matrix applies, but timing is bounded only by token-manager retry settings and per-attempt HTTP timeouts

Token-response requirements:

- grant type is `client_credentials`
- required response fields are `access_token`, `token_type`, and `expires_in`
- `token_type` must be `Bearer`, matched case-insensitively
- responses missing those fields or providing unusable expiry metadata are rejected

Access-token handling rules:

- access tokens are treated as opaque bearer tokens plus expiry metadata
- the participant does not parse or locally verify the access token
- the participant sends `Authorization: Bearer <access_token>` to the resource server
- the token endpoint validates the `private_key_jwt` client assertion
- the resource server validates the access token
- the participant validates response shape, token type, expiry metadata, and auth-failure signals

Timeout rules:

- for foreground work, effective connect timeout is `min(connect-timeout, remaining max-total-timeout)`
- for foreground work, effective request timeout is `min(request-timeout, remaining max-total-timeout)`
- if no positive budget remains, the request is not started
- background refresh uses the configured per-attempt timeouts and never borrows time from a business request

## Config Model

`ExtensionServiceConfig` should be split into explicit blocks for:

- resource-server endpoint
- auth mode and auth-specific settings
- business-request transport lifecycle
- declared functions

The final config reuses Canton endpoint vocabulary rather than inventing a second transport dialect:

- `address`
- `port`
- `tls`

Implementation rules:

- `external_call` introduces its own endpoint ADTs under participant config; it does not literally embed `FullClientConfig`
- those ADTs reuse only the existing field vocabulary and TLS semantics
- `keepAliveClient` is not part of the `external_call` contract
- PureConfig readers and writers remain local to the new `ExtensionServiceConfig`-specific case classes

### Auth modes and TLS rules

Supported auth modes:

- `none`
- `oauth`

`auth.mode = oauth` is the canonical production path. When OAuth is enabled:

- TLS is required for both the resource server and the token endpoint
- plaintext `http` endpoints are rejected during config validation
- any insecure or trust-all hook remains test-only scaffolding and is not part of the supported OAuth contract

Rotation application points:

- signing keys are re-read when a new client assertion is produced
- token-endpoint and resource-server trust material is loaded when the corresponding HTTP client is built
- replacing trust material therefore takes effect on participant restart, not via hot reload

### Token-endpoint URI derivation

The token-endpoint config derives one canonical HTTPS URI string.

Derivation rules:

- scheme is always `https`
- host is the configured `address`, copied exactly
- port is omitted when it is `443`
- otherwise port is included as `:<port>`
- path is the configured `path`, copied exactly

Validation rules:

- `path` must start with `/`
- `path` must not contain a query string
- `path` must not contain a fragment
- no dot-segment normalization, trailing-slash rewriting, or host rewriting is performed

That canonical URI is used both as the actual token-endpoint request target and as the `aud` claim in the `private_key_jwt` client assertion. This design does not introduce a separate client-assertion audience field.

### Retry and timeout ownership

Business-request transport settings remain top-level extension settings:

- `connect-timeout`
- `request-timeout`
- `max-total-timeout`
- `max-retries`
- `retry-initial-delay`
- `retry-max-delay`

These settings continue to govern the resource-server call path and the outer retry loop owned by `HttpExtensionServiceClient`.

Auth lifecycle retries are configured separately under `auth.oauth.token-manager` using `AuthenticationTokenManagerConfig`:

- `refresh-auth-token-before-expiry`
- `retries`
- `min-retry-interval`
- optional exponential-backoff settings

This split is intentional: extension-level retries govern replay of business calls to the resource server, while token-manager retries govern acquisition and refresh of OAuth tokens.

### Example config

```hocon
extensions = {
  test-ext = {
    name = "test-ext"

    endpoint = {
      address = "ext.example.internal"
      port = 443
      tls = {
        trust-collection-file = "/etc/canton/ext-ca.pem"
      }
    }

    auth = {
      mode = oauth

      oauth = {
        token-endpoint = {
          address = "issuer.example.internal"
          port = 443
          path = "/oauth2/token"
          tls = {
            trust-collection-file = "/etc/canton/issuer-ca.pem"
          }
        }
        audience = "ext.example.internal"
        scope = "external.call.invoke"
        token-manager = {
          refresh-auth-token-before-expiry = 20s
          retries = 20
          min-retry-interval = 500ms
        }
        client-id = "participant1"
        key-id = "participant1-key"
        private-key-file = "/etc/canton/oauth-client-key.der"
      }
    }

    connect-timeout = 500ms
    request-timeout = 8s
    max-total-timeout = 25s
    max-retries = 3
    retry-initial-delay = 1s
    retry-max-delay = 10s
    request-id-header = "X-Request-Id"
  }
}
```

## OAuth Client Authentication

The first implementation supports one production client-auth mechanism: `private_key_jwt`.

Concrete contract:

- grant type is always `client_credentials`
- the token request includes `client_assertion_type = urn:ietf:params:oauth:client-assertion-type:jwt-bearer`
- the token request includes `client_assertion = <signed JWT>`
- JWT header uses `alg = RS256`
- `key-id` is optional and is emitted as `kid` when present
- JWT claims are:
  - `iss = client-id`
  - `sub = client-id`
  - `aud = <canonical token-endpoint URI>`
  - `iat = now`
  - `exp = now + 30s`
  - `jti = <fresh random identifier>`
- the participant never reuses a previously signed client assertion
- `scope` is optional and is sent as a token-request field when present
- `audience` is optional and is sent as a token-request field when present
- signed assertions, key material, and key-file contents are never logged or persisted

Key-handling rules:

- RSA DER/PKCS8 is the only supported signing-key format
- private keys are re-read whenever a new client assertion is produced, so key rotation takes effect on the next token acquisition
- assertion lifetime is fixed and short in the first implementation rather than becoming a new operator-facing knob

Accepted tradeoff:

- issued access tokens are still bearer tokens and remain replayable until expiry if exfiltrated
- this is accepted in exchange for a simpler and more canonical external-call auth contract

## Request, Retry, and Determinism Rules

### One outer attempt

For one outer external-call attempt:

1. `HttpExtensionServiceClient` computes the absolute deadline from `max-total-timeout`.
2. It asks the auth provider to prepare auth for that deadline.
3. It sends the request to `/api/v1/external-call` with the existing `X-Daml-External-*` headers unchanged, the auth header from `PreparedAuth`, and effective timeouts clamped to the remaining deadline.
4. If the response is not `401`, that response is the outcome of the outer attempt.
5. If the response is `401`, the client builds `AuthResponseContext` from the concrete `HttpResponse` and the participant-generated resource request id.
6. `handleResponse` may request one auth-local replay by returning `ReplayOnceWithFreshAuth`.
7. The final response produced by that replay, or the original non-`401` response, becomes the outcome of the outer attempt.
8. The outer retry loop then classifies that outer-attempt outcome as success, retryable failure, or terminal failure.

### `401` invalidation and replay

Invalidation policy:

- invalidate cached OAuth state on `401 Unauthorized` only if the rejected token still matches the provider's current token
- replay the same business request once with a freshly acquired token, subject to the same outer deadline
- do not invalidate on `403`, `404`, `429`, `5xx`, timeouts, or transport failures
- record `WWW-Authenticate` when present for diagnostics, but do not depend on it for invalidation

After the single auth-local replay:

- `200` succeeds
- `401`, `400`, `403`, or `404` end the outer attempt terminally
- `408`, `429`, `500`, `502`, `503`, and `504` become retryable outer-attempt outcomes
- transport failures mapped to retryable statuses behave the same way
- token-acquisition failures are classified from the structured auth failure, not from the later flattened HTTP status code

### Retry ownership

`HttpExtensionServiceClient.callWithRetry` remains the only business-request retry loop.

Rules:

- `maxRetries` counts only completed outer retries
- an outer attempt may include one initial resource request and at most one auth-local replay
- the auth-local replay does not consume a `maxRetries` slot
- any retryable result produced after the replay is the final result of that outer attempt
- if the outer loop retries after that result, that consumes one `maxRetries` slot
- auth-failure retryability is decided from the structured auth failure before any engine-facing boundary flattening

### Determinism

OAuth metadata must not enter Daml-level business semantics.

Unchanged inputs:

- business request path
- `X-Daml-External-Function-Id`
- `X-Daml-External-Config-Hash`
- `X-Daml-External-Mode`
- request and response body contract
- submission versus validation business meaning

Operational-only changes:

- outbound auth headers
- token-acquisition traffic

Invariant:

- for a fixed `(extensionId, functionId, configHash, input, mode)`, successful business responses must not depend on access-token claims, client-assertion timestamps, `jti`, or OAuth client identity
- OAuth controls whether the participant may reach the service, but once authorized it must not behave as an additional business input

## Error Model and Boundary Mapping

Internally, the participant should distinguish:

- token acquisition failure
- token rejection by the resource server
- resource-server transport failure
- resource-server application error

Implementation rule:

- keep the internal error ADT structured
- flatten to the current `ExternalCallError` shape only at the boundary to the Daml engine
- use the structured classes for logs, metrics, and retry decisions

### Auth failure classes

`ExternalCallAuthFailure` should carry a message and an optional token-endpoint request id. Concrete cases:

- `TokenEndpointHttpFailure(statusCode, message, requestId)`
- `TokenEndpointTimeout(message, requestId)`
- `TokenEndpointIoFailure(message, requestId)`
- `TokenEndpointTlsFailure(message, requestId)`
- `MalformedTokenResponse(message, requestId)`
- `UnsupportedTokenType(message, requestId)`
- `ClientAssertionSigningFailure(message)`
- `LocalAuthMaterialFailure(message)`

Class rules:

- `UnsupportedTokenType` is used only when `token_type` is present but not `Bearer`
- `MalformedTokenResponse` covers missing or unusable token-response fields, including unusable expiry metadata
- `ClientAssertionSigningFailure` is limited to local signing failures while building `private_key_jwt`
- `LocalAuthMaterialFailure` covers other local auth-material and key-loading failures

### Engine-facing mapping

`ExtensionServiceExternalCallHandler` continues to expose only `statusCode`, `message`, and `requestId`.

Flattening rules:

- token acquisition failure
  - `TokenEndpointHttpFailure` preserves the HTTP status code
  - `TokenEndpointTimeout` maps to `408`
  - `TokenEndpointIoFailure` maps to `503`
  - `TokenEndpointTlsFailure` maps to `503`
  - `ClientAssertionSigningFailure` maps to `500`
  - `LocalAuthMaterialFailure` maps to `500`
  - `UnsupportedTokenType` maps to `502`
  - `MalformedTokenResponse` maps to `502`
  - prefix the message with `OAuth token acquisition failed:`
- token rejection by the resource server
  - after auth-local replay is exhausted, return `401`
  - use the message `Unauthorized - OAuth token rejected by resource server`
- resource-server transport failure
  - preserve the current transport-derived status mapping and messages from `HttpExtensionServiceClient`
- resource-server application error
  - preserve the resource server's HTTP status code
  - preserve the current body-to-message mapping from `HttpExtensionServiceClient`

Request-id rules:

- the boundary `requestId` is the participant-generated outbound correlation id from the last HTTP interaction that determined the final failure for the outer attempt
- `ExternalCallAuthFailure.requestId` is the participant-generated token-endpoint request id when token-endpoint HTTP work had already started
- if the final failure is an auth failure before any resource request is sent, return `authFailure.requestId`
- if the final failure is the initial resource response and no auth-local replay occurs, return the initial resource request id
- if a `401` triggers token acquisition and then a replay request is sent, the replay request id supersedes both the original resource request id and the token-endpoint request id
- if token acquisition after a `401` fails before a replay request is sent, return `authFailure.requestId` when present, otherwise the original `401` resource request id
- if failure occurs before any HTTP request is sent, return `None`

## Startup Validation

The current startup-validation wiring is incomplete: `EngineExtensionsConfig` already carries validation-related settings and `ExtensionServiceManager.validateAllExtensions()` exists, but `ParticipantNode` does not currently await it during startup. This design makes startup validation explicit and replaces the current booleans with one global mode while keeping `echoMode` as a test-only bypass.

### Validation mode and result type

Introduce one global validation field:

- `validation-mode = off | local | best-effort-remote | strict-remote`

Keep `EngineExtensionsConfig.echoMode` unchanged as a test-only bypass.

Replace `ExtensionValidationResult.Valid | Invalid(errors)` with a structured per-extension report:

```scala
final case class ExtensionValidationReport(
  extensionId: String,
  localErrors: Seq[String],
  remoteErrors: Seq[String],
  remoteWarnings: Seq[String],
)
```

Semantics:

- `localErrors` are fatal in every mode except `off`
- `remoteErrors` are fatal only in `strict-remote`
- `remoteWarnings` never block startup

API rules:

- `ExtensionServiceClient.validateConfiguration(validationMode)` returns `ExtensionValidationReport`
- `ExtensionServiceManager.validateAllExtensions()` returns `Map[String, ExtensionValidationReport]`
- `ExtensionServiceManager` never decides startup success or failure; `ParticipantNode` interprets the aggregated report against `validation-mode`

### Echo mode

When `echoMode = true`:

- `ExtensionServiceManager` instantiates `EchoExtensionServiceClient` for every extension
- no HTTP clients, auth providers, token managers, or remote probes are constructed
- if `validation-mode = off`, `validateAllExtensions()` returns no report
- otherwise it returns one empty-success report per configured extension:
  - `localErrors = Seq.empty`
  - `remoteErrors = Seq.empty`
  - `remoteWarnings = Seq.empty`

Startup therefore succeeds in every validation mode when `echoMode = true`.

### Validation behavior by mode

- `off`
  - skip startup validation entirely
  - produce no startup report
- `local`
  - validate config completeness and mutual consistency
  - load private keys and TLS trust material
  - build TLS contexts
  - fail startup on any local validation error
  - local validation failures include malformed config, mutually inconsistent config, unreadable keys, unreadable trust material, and invalid TLS material
- `best-effort-remote`
  - do all local validation
  - run remote checks
  - fail startup on any local validation error
  - surface remote validation failures as warnings but do not fail startup
- `strict-remote`
  - do the same checks as `best-effort-remote`
  - fail startup on any local or remote validation error

Mixed-extension semantics:

- one global `validation-mode` applies to every configured extension
- `auth.mode = none` runs transport/config validation only
- `auth.mode = oauth` runs transport/config validation plus OAuth-specific validation
- in remote modes, `auth.mode = none` performs only the resource-server transport probe
- in remote modes, `auth.mode = oauth` performs real token acquisition plus the resource-server transport probe

### Validation algorithm and remote probe

Startup algorithm:

1. sort extensions by extension id
2. run local validation for every extension
3. record local failures and skip remote validation for those extensions
4. run the remote checks required by `validation-mode` for the remaining extensions
5. aggregate results deterministically by extension id

Remote validation must not send a synthetic business request through `/api/v1/external-call`.

Remote probe rules:

- token-endpoint validation performs a real token acquisition
- resource-server validation uses a dedicated transport-validation helper, not `HttpExtensionServiceClient`
- that helper performs only DNS resolution, TCP connect, and, when TLS is enabled, SSL/TLS handshake using the same trust material as the runtime client
- it does not send an HTTP method, path, body, or headers
- it does not send `X-Daml-External-*` headers and does not invoke a Daml function such as `_health`

## Implementation Impact

No compatibility layer is required for existing users, but the repository still needs an internal migration to the new config and validation model.

### Main implementation areas

- participant node startup and lifecycle
  - pass `Clock` into `ExtensionServiceManager`
  - await startup validation before exposing services
  - register the extension manager in the node closeable set
- extension config model
  - replace legacy transport/auth fields with explicit endpoint and auth config
  - replace validation booleans with `validationMode`
- extension manager
  - instantiate auth providers
  - own auth-provider lifecycle
  - execute validation across all extensions
  - stop relying on one globally shared `HttpClient` for all TLS and auth cases
- HTTP extension client
  - remove token lifecycle logic
  - rewrite the outer retry loop around async `FutureUnlessShutdown` composition
  - integrate auth-provider calls and structured auth-failure handling
  - clamp connect and request timeouts to the remaining outer deadline
- extension service interface
  - replace `ExtensionValidationResult` with `ExtensionValidationReport`
- new supporting code
  - add the auth package
  - add a transport-validation helper

### Repo-wide migration work

- update participant test fixtures and helpers that construct `ExtensionServiceConfig` directly
- update external-call integration tests to use the new endpoint and auth config shape
- update sample configs and documentation-backed test resources that still use legacy fields
- replace `_health`-based validation tests with tests for structured validation reports and transport-only probing
- remove external-call-specific assumptions about static bearer tokens and `tlsInsecure`

## Observability and Testing

### Logging and metrics

Add structured logs for:

- token acquisition start, success, and failure
- token refresh
- token invalidation
- auth rejection on `401`
- final external-call failure classification

Safe log dimensions:

- extension id
- auth mode
- audience
- scope
- status code
- request id

Never log:

- access tokens
- refresh tokens
- client assertions
- client secrets
- private key material

Add participant metrics under a new external-call subtree:

- token acquisition success count
- token acquisition failure count
- token refresh count
- token invalidation count
- token cache hit and miss count
- auth latency timer

### Tests

Unit coverage:

- auth config parsing and exclusivity
- OAuth token acquisition success and failure
- concurrent callers sharing one token acquisition
- pre-expiry refresh
- token-conditional invalidation on `401`
- audience and scope propagation
- private-key and certificate loading failures
- deadline composition between auth and business retries
- fixed-lifetime `private_key_jwt` construction
- background refresh using an injected clock rather than wall-clock sleeps

Integration coverage:

- unauthenticated external calls still work under `auth.mode = none`
- OAuth-protected calls succeed end to end
- expired tokens refresh successfully
- `401` invalidates the cached token and replays the same business request once with a fresh token
- submission and validation both succeed under the same OAuth config
- submission and validation produce the same business response even though access tokens and client assertions differ between runs
- signing-key rotation takes effect on the next token acquisition
- resource-server or token-endpoint certificate rotation takes effect after participant restart
- remote validation does not send a business `_health` call

Testing infrastructure requirements:

- auth lifecycle tests use an injectable clock
- token issuance is mocked through a dedicated token client or `MockOAuthServer`, not through the resource-server mock
