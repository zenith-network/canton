# External Call OAuth Tech Spec

## Status

Draft for iterative refinement.

This document describes the target design for adding OAuth-based service-to-service authentication to participant external calls. It is intentionally organized so each normative rule has a single home: runtime behavior, config semantics, error mapping, and startup validation are each defined once.

## Goal and Scope

Add OAuth-based authentication to participant external calls without changing the Daml external-call business protocol.

This spec is grounded in the current participant external-call, auth, and validation implementation. The following decisions are assumed throughout:

- The canonical production contract is `auth.mode = oauth` with `private_key_jwt` client authentication over TLS.
- OAuth changes transport and authentication behavior only. It must not change Daml-level business semantics.
- OAuth configuration remains per extension.
- Outbound OAuth may reuse audience and scope semantics already present elsewhere in Canton auth, but it does not reference named identity-provider definitions directly.
- This design assumes there are no existing external-call users to preserve, so the final config may replace the current static-token shape directly. No compatibility alias layer is required.
- Sender-constrained mechanisms such as mTLS are out of scope.

Determinism requirement:

- For a fixed `(extensionId, functionId, configHash, input, mode)`, successful business responses must not depend on access-token claims, client-assertion timestamps, `jti`, or OAuth client identity.
- OAuth decides whether the participant may reach the service. Once authorized, it must not behave as an extra business input.

## Current Baseline and Design Constraints

The current external-call path is:

1. `ParticipantNode` creates one `ExtensionServiceManager` when `parameters.engine.extensions` is non-empty.
2. `ExtensionServiceManager` creates one `HttpExtensionServiceClient` per configured extension.
3. `ExtensionServiceExternalCallHandler` forwards Daml engine calls to the manager.
4. `HttpExtensionServiceClient` builds the HTTP request, injects the current auth header, performs the transport call, classifies the response, and owns request retry logic.

The refactor is driven by the following constraints:

- `ExtensionServiceConfig` currently mixes resource-server transport, auth material, and transport lifecycle settings in one case class.
- `HttpExtensionServiceClient` currently reads a literal token from `jwt` or `jwtFile`, injects `Authorization: Bearer <token>` on every request, treats `401` as terminal, and has no token expiry, refresh, invalidation, or concurrent acquisition control.
- `HttpExtensionServiceClient.callWithRetry` is already the correct outer retry boundary and must remain so. Retryable outcomes are `408`, `429`, `500`, `502`, `503`, and `504`. Terminal outcomes are `400`, `401`, `403`, and `404`.
- Existing Canton pieces are reusable as patterns, not as drop-in types: JWT audience and scope semantics in `AuthServiceConfig` and `AuthServiceJWT`, JWKS retrieval and caching in `CachedJwtVerifierLoader`, token lifecycle semantics in `AuthenticationTokenManager`, JWT signing helpers in `com.daml.jwt.JwtSigner` and `KeyUtils`, and TLS trust configuration semantics in existing `TlsClientConfig` usage.
- `AuthenticationTokenManager` cannot be reused literally for OAuth access tokens because it is typed around sequencer `AuthenticationToken`, which is a fixed-length binary token with a gRPC-oriented error surface. The lifecycle pattern is reusable; the concrete types are not.

## Proposed Runtime Design

### Components and Ownership

Add a dedicated participant extension auth package and move all auth-specific behavior behind a provider boundary. The auth layer is responsible for:

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

Provider hot-path contract:

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

- `prepareRequest` performs any foreground token acquisition needed before the first resource-server request of the current outer attempt.
- `prepareRequest` receives the absolute outer deadline and must clamp token-endpoint work to that deadline.
- `PreparedAuth.tokenUsed` is the exact token attached to the outgoing request and is the value used for token-conditional invalidation.
- `PreparedAuth.tokenEndpointRequestId` records the last participant-generated token-endpoint request id involved in preparing auth for the current outer attempt.
- `AuthResponseContext` carries only the metadata the auth layer may inspect: status code, participant-generated resource request id, and the first `WWW-Authenticate` header when present.
- Request ids in this design are participant-generated outbound correlation ids. Servers may echo them, but the protocol does not depend on a response-header request-id contract.
- `handleResponse` may invalidate cached auth state and invoke the same foreground-acquisition path used by `prepareRequest` to obtain fresh auth for one auth-local replay.

Lifecycle ownership:

- `ExtensionServiceManager` owns auth providers and passes a `Clock` plus `isClosing` signal into `OAuthAccessTokenManager`.
- `ExtensionServiceManager.onClosed()` closes auth providers so background refresh stops during shutdown.
- `ParticipantNode` passes its existing clock into the manager and registers the extension manager as a closeable so `onClosed()` always runs.

### Request Flow, Retry Ownership, and Deadlines

`HttpExtensionServiceClient` keeps the existing resource-server protocol: endpoint shape `/api/v1/external-call`, `X-Daml-External-*` headers, participant-generated request ids, transport execution, response classification, and the outer retry budget.

It stops owning token file loading, token caching and refresh, token invalidation policy, and token-endpoint request construction.

For one external-call operation, `HttpExtensionServiceClient` computes one absolute deadline from `max-total-timeout` before the first outer attempt. Every outer attempt uses the remaining budget against that fixed deadline.

One outer attempt is:

1. Compute the remaining budget against the operation deadline.
2. Ask the auth provider to prepare auth for that deadline.
3. Send the request to `/api/v1/external-call` with the existing `X-Daml-External-*` headers unchanged, the auth header from `PreparedAuth`, and connect and request timeouts clamped to the remaining budget.
4. If the response is not `401`, that response is the outcome of the outer attempt.
5. If the response is `401`, build `AuthResponseContext` from the concrete `HttpResponse` and the participant-generated resource request id.
6. Let `handleResponse` decide whether to fail auth or replay once with fresh auth.
7. Treat the replay result, or the original non-`401` response, as the outcome of the outer attempt.
8. Feed that outer-attempt outcome back into the existing retry loop.

Runtime rules:

- The outer retry loop remains the only business-request retry loop.
- `maxRetries` counts only completed outer retries.
- An outer attempt may include one initial resource request and at most one auth-local replay.
- The auth-local replay does not consume a `maxRetries` slot.
- Any retryable result produced after the replay is the final result of that outer attempt.
- If the outer loop retries after that result, that consumes one `maxRetries` slot.
- `401` is the only resource response that may trigger auth-specific recovery after a resource request has been sent.
- Invalidate cached OAuth state on `401` only if the rejected token still matches the provider's current token.
- Do not invalidate on `403`, `404`, `429`, `5xx`, timeouts, or transport failures.
- Record `WWW-Authenticate` when present for diagnostics, but do not depend on it for invalidation.
- After the single auth-local replay, `200` succeeds; `401`, `400`, `403`, and `404` are terminal; `408`, `429`, `500`, `502`, `503`, and `504` remain retryable outer-attempt outcomes; transport failures mapped to retryable statuses behave the same way.
- Token-acquisition failures are classified from the structured auth failure, not from a later flattened HTTP status code.
- If no positive deadline budget remains, neither token acquisition nor a resource request is started.

Because both auth-provider calls are `FutureUnlessShutdown`, the outer retry loop must be rewritten around asynchronous `FutureUnlessShutdown` composition. Retry delays must not use `Threading.sleep`. The actual HTTP send may remain blocking internally or move to `sendAsync`, but attempt orchestration must be async.

### OAuth Token Acquisition and Caching

Add an OAuth-specific access-token manager that reuses `AuthenticationTokenManager` lifecycle semantics directly through `AuthenticationTokenManagerConfig`.

Concrete token type:

- `OAuthAccessTokenWithExpiry(accessToken: String, expiresAt: CantonTimestamp, tokenType: String)`

`OAuthAccessTokenManager` behavior:

- lazy first acquisition
- one shared in-flight acquisition or refresh
- cached token with expiry
- background refresh before expiry
- explicit invalidation
- retry and backoff during acquisition

Foreground and background rules:

- Foreground acquisition is driven by a business request and uses the remaining outer deadline from `HttpExtensionServiceClient`.
- Background refresh is not tied to a business request; it is bounded only by token-manager retry settings and per-attempt HTTP timeouts.
- Failed background refresh clears the cached token, matching `AuthenticationTokenManager` semantics. The next business request then performs foreground acquisition.

Shared in-flight rules:

- `OAuthAccessTokenManager` preserves one shared in-flight fetch, whether it was started in the foreground or background.
- A second caller waits on that shared future rather than starting a second token-endpoint request.
- Each waiting business request still enforces its own outer deadline while waiting.
- If one waiting caller times out, it fails locally without cancelling the shared fetch.
- A shared fetch that later succeeds populates the cache for later callers.

`OAuthTokenClient` is a dedicated HTTP client for the token endpoint. It:

- talks only to the configured token endpoint
- uses its own TLS settings
- generates a participant-local request id for each token-endpoint interaction and sends it in the configured request-id header
- receives an absolute deadline for foreground acquisition
- returns `OAuthAccessTokenWithExpiry`
- never logs secret-bearing inputs or outputs

Token-endpoint retryability is specific to this design and does not inherit the gRPC exception policy used elsewhere.

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

- `429` honors `Retry-After` when present, capped by token-manager retry settings.
- During foreground acquisition, every retry attempt and delay must fit within the caller's remaining outer deadline.
- During background refresh, the same retryability matrix applies, but timing is bounded only by token-manager retry settings and per-attempt HTTP timeouts.

Token request and response rules:

- grant type is `client_credentials`
- required response fields are `access_token`, `token_type`, and `expires_in`
- `token_type` must be `Bearer`, matched case-insensitively
- responses missing those fields or providing unusable expiry metadata are rejected
- access tokens are treated as opaque bearer tokens plus expiry metadata
- the participant does not parse or locally verify the access token
- the participant sends `Authorization: Bearer <access_token>` to the resource server
- the token endpoint validates the `private_key_jwt` client assertion
- the resource server validates the access token
- the participant validates response shape, token type, expiry metadata, and auth-failure signals

Client authentication rules:

- The first implementation supports one production client-auth mechanism: `private_key_jwt`.
- The token request includes `client_assertion_type = urn:ietf:params:oauth:client-assertion-type:jwt-bearer`.
- The token request includes `client_assertion = <signed JWT>`.
- JWT header uses `alg = RS256`.
- `key-id` is optional and is emitted as `kid` when present.
- JWT claims are `iss = client-id`, `sub = client-id`, `aud = <canonical token-endpoint URI>`, `iat = now`, `exp = now + 30s`, and `jti = <fresh random identifier>`.
- The participant never reuses a previously signed client assertion.
- `scope` is optional and is sent as a token-request field when present.
- `audience` is optional and is sent as a token-request field when present.
- Signed assertions, key material, and key-file contents are never logged or persisted.

Key-handling rules:

- RSA DER/PKCS8 is the only supported signing-key format.
- Private keys are re-read whenever a new client assertion is produced, so key rotation takes effect on the next token acquisition.
- Assertion lifetime is fixed and short in the first implementation rather than becoming a new operator-facing knob.

Accepted tradeoff: issued access tokens are still bearer tokens and remain replayable until expiry if exfiltrated. This is accepted in exchange for a simpler and more canonical external-call auth contract.

## Config Model

`ExtensionServiceConfig` should be split into explicit blocks for resource-server endpoint, auth mode and auth-specific settings, business-request transport lifecycle, and declared functions.

The final config reuses Canton endpoint vocabulary rather than inventing a second transport dialect:

- `address`
- `port`
- `tls`

Implementation rules:

- `external_call` introduces its own endpoint ADTs under participant config; it does not literally embed `FullClientConfig`.
- Those ADTs reuse the existing field vocabulary and TLS semantics.
- `keepAliveClient` is not part of the `external_call` contract.
- PureConfig readers and writers remain local to the new `ExtensionServiceConfig`-specific case classes.

Auth and endpoint rules:

- Supported auth modes are `none` and `oauth`.
- `auth.mode = oauth` is the canonical production path.
- When OAuth is enabled, TLS is required for both the resource server and the token endpoint.
- Plaintext `http` endpoints are rejected during config validation.
- Any insecure or trust-all hook remains test-only scaffolding and is not part of the supported OAuth contract.

The token-endpoint config derives one canonical HTTPS URI string. Scheme is always `https`. Host is the configured `address`, copied exactly. Port is omitted when it is `443`; otherwise it is included as `:<port>`. Path is the configured `path`, copied exactly. The path must start with `/` and must not contain a query string or fragment. No dot-segment normalization, trailing-slash rewriting, or host rewriting is performed. That canonical URI is used both as the actual token-endpoint request target and as the `aud` claim in the `private_key_jwt` client assertion. This design does not introduce a separate client-assertion audience field.

Transport ownership:

- Business-request transport settings remain top-level extension settings: `connect-timeout`, `request-timeout`, `max-total-timeout`, `max-retries`, `retry-initial-delay`, `retry-max-delay`, and `request-id-header`.
- `connect-timeout` and `request-timeout` are the shared per-attempt HTTP timeout settings for both resource-server calls and token-endpoint calls.
- During a foreground attempt, the effective connect timeout is `min(connect-timeout, remaining max-total-timeout)` and the effective request timeout is `min(request-timeout, remaining max-total-timeout)`.
- `max-total-timeout` is the outer budget for one business external-call operation, including foreground token acquisition and one allowed `401` replay.
- `max-retries`, `retry-initial-delay`, and `retry-max-delay` are owned only by the outer business-request retry loop in `HttpExtensionServiceClient`.
- Auth lifecycle retries are configured separately under `auth.oauth.token-manager` using `AuthenticationTokenManagerConfig`: `refresh-auth-token-before-expiry`, `retries`, `min-retry-interval`, and optional exponential-backoff settings.

Rotation application points:

- signing keys are re-read when a new client assertion is produced
- token-endpoint and resource-server trust material is loaded when the corresponding HTTP client is built
- replacing trust material therefore takes effect on participant restart, not via hot reload

### Example Config

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

## Error Handling and Boundary Mapping

Internally, the participant should distinguish token acquisition failure, token rejection by the resource server, resource-server transport failure, and resource-server application error.

Implementation rules:

- keep the internal error ADT structured
- flatten to the current `ExternalCallError` shape only at the boundary to the Daml engine
- use the structured classes for logs, metrics, and retry decisions

`ExternalCallAuthFailure` should carry a message and an optional token-endpoint request id. Concrete cases:

- `TokenEndpointHttpFailure(statusCode, message, requestId)`
- `TokenEndpointTimeout(message, requestId)`
- `TokenEndpointIoFailure(message, requestId)`
- `TokenEndpointTlsFailure(message, requestId)`
- `MalformedTokenResponse(message, requestId)` for missing or unusable token-response fields, including unusable expiry metadata
- `UnsupportedTokenType(message, requestId)` when `token_type` is present but not `Bearer`
- `ClientAssertionSigningFailure(message)` for local signing failures while building `private_key_jwt`
- `LocalAuthMaterialFailure(message)` for other local auth-material and key-loading failures

`ExtensionServiceExternalCallHandler` continues to expose only `statusCode`, `message`, and `requestId`.

Flattening rules:

- Token acquisition failure maps as follows:
  - `TokenEndpointHttpFailure` preserves the HTTP status code.
  - `TokenEndpointTimeout` maps to `408`.
  - `TokenEndpointIoFailure` maps to `503`.
  - `TokenEndpointTlsFailure` maps to `503`.
  - `ClientAssertionSigningFailure` maps to `500`.
  - `LocalAuthMaterialFailure` maps to `500`.
  - `UnsupportedTokenType` maps to `502`.
  - `MalformedTokenResponse` maps to `502`.
  - Prefix the message with `OAuth token acquisition failed:`.
- Token rejection by the resource server maps to `401` after auth-local replay is exhausted and uses the message `Unauthorized - OAuth token rejected by resource server`.
- Resource-server transport failures preserve the current transport-derived status mapping and messages from `HttpExtensionServiceClient`.
- Resource-server application errors preserve the resource server's HTTP status code and the current body-to-message mapping from `HttpExtensionServiceClient`.

Boundary request-id rule:

- The boundary `requestId` is the participant-generated outbound correlation id from the last HTTP interaction that determined the final failure for the outer attempt.
- `ExternalCallAuthFailure.requestId` is the participant-generated token-endpoint request id when token-endpoint HTTP work had already started.
- If the final failure happens before any resource request is sent, return `authFailure.requestId` when present, otherwise `None`.
- If a resource response ends the attempt without an auth-local replay, return that resource request id.
- If a `401` triggers token acquisition and a replay request is sent, the replay request id supersedes both the original resource request id and the token-endpoint request id.
- If token acquisition after a `401` fails before a replay request is sent, return `authFailure.requestId` when present, otherwise the original `401` resource request id.

## Startup Validation

The current startup-validation wiring is incomplete: `EngineExtensionsConfig` already carries validation-related settings and `ExtensionServiceManager.validateAllExtensions()` exists, but `ParticipantNode` does not currently await it during startup. This design makes startup validation explicit and replaces the current booleans with one global mode while keeping `echoMode` as a test-only bypass.

Validation mode:

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

- `localErrors` are fatal in every mode except `off`.
- `remoteErrors` are fatal only in `strict-remote`.
- `remoteWarnings` never block startup.
- Remote validation failures produced by clients always populate `remoteErrors`.
- In `best-effort-remote`, `ParticipantNode` downgrades those reported errors only when interpreting the aggregated report. Clients should not rewrite them into `remoteWarnings` based on mode.

API rules:

- `ExtensionServiceClient.validateConfiguration(validationMode)` returns `ExtensionValidationReport`.
- `ExtensionServiceManager.validateAllExtensions()` returns `Map[String, ExtensionValidationReport]`.
- `ExtensionServiceManager` never decides startup success or failure. `ParticipantNode` interprets the aggregated report against `validation-mode`.

Mode behavior:

- `off`: skip startup validation entirely and produce no startup report.
- `local`: validate config completeness and mutual consistency, load private keys and TLS trust material, build TLS contexts, and fail startup on any local validation error.
- `best-effort-remote`: do all local validation, then run remote checks. Fail startup on local validation errors only and surface remote validation failures as warnings.
- `strict-remote`: do the same checks as `best-effort-remote` and fail startup on any local or remote validation error.

Local validation failures include malformed config, mutually inconsistent config, unreadable keys, unreadable trust material, and invalid TLS material.

Per-auth-mode remote behavior:

- `auth.mode = none` performs transport and config validation only. In remote modes it performs only the resource-server transport probe.
- `auth.mode = oauth` performs transport and config validation plus OAuth-specific validation. In remote modes it performs real token acquisition and the resource-server transport probe.

Echo mode behavior:

- `ExtensionServiceManager` instantiates `EchoExtensionServiceClient` for every extension.
- No HTTP clients, auth providers, token managers, or remote probes are constructed.
- If `validation-mode = off`, `validateAllExtensions()` returns no report.
- Otherwise it returns one empty-success report per configured extension with empty `localErrors`, `remoteErrors`, and `remoteWarnings`.

Startup algorithm:

1. Sort extensions by extension id.
2. Run local validation for every extension.
3. Record local failures and skip remote validation for those extensions.
4. Run the remote checks required by `validation-mode` for the remaining extensions.
5. Aggregate results deterministically by extension id.

Remote validation must not send a synthetic business request through `/api/v1/external-call`.

Remote probe rules:

- Token-endpoint validation performs a real token acquisition.
- Resource-server validation uses a dedicated transport-validation helper, not `HttpExtensionServiceClient`.
- That helper performs only DNS resolution, TCP connect, and, when TLS is enabled, SSL/TLS handshake using the same trust material as the runtime client.
- It does not send an HTTP method, path, body, or headers.
- It does not send `X-Daml-External-*` headers and does not invoke a Daml function such as `_health`.

## Impacted Components

No compatibility layer is required for existing users, but the repository still needs an internal migration to the new config and validation model.

Main implementation areas:

- participant node startup and lifecycle: pass `Clock` into `ExtensionServiceManager`, await startup validation before exposing services, and register the extension manager in the node closeable set
- extension config model: replace legacy transport and auth fields with explicit endpoint and auth config and replace validation booleans with `validationMode`
- extension manager: instantiate auth providers, own auth-provider lifecycle, execute validation across all extensions, and stop relying on one globally shared `HttpClient` for all TLS and auth cases
- HTTP extension client: remove token lifecycle logic, rewrite the outer retry loop around async `FutureUnlessShutdown` composition, integrate auth-provider calls and structured auth-failure handling, and clamp timeouts to the remaining outer deadline
- extension service interface and helpers: replace `ExtensionValidationResult` with `ExtensionValidationReport`, add the auth package, and add a transport-validation helper

Repo-wide migration work:

- update participant test fixtures and helpers that construct `ExtensionServiceConfig` directly
- update external-call integration tests to use the new endpoint and auth config shape
- update sample configs and documentation-backed test resources that still use legacy fields
- replace `_health`-based validation tests with tests for structured validation reports and transport-only probing
- remove external-call-specific assumptions about static bearer tokens and `tlsInsecure`

## Observability and Testing

Logging requirements:

- add structured logs for token acquisition start, success, and failure; token refresh; token invalidation; auth rejection on `401`; and final external-call failure classification
- safe log dimensions are extension id, auth mode, audience, scope, status code, and request id
- never log access tokens, refresh tokens, client assertions, client secrets, or private key material

Metrics requirements:

- add participant metrics under a new external-call subtree
- include token acquisition success count, token acquisition failure count, token refresh count, token invalidation count, token cache hit and miss count, and an auth latency timer

Test requirements:

- unit coverage for auth config parsing and exclusivity, OAuth token acquisition success and failure, concurrent callers sharing one token acquisition, pre-expiry refresh, token-conditional invalidation on `401`, audience and scope propagation, private-key and certificate loading failures, deadline composition between auth and business retries, fixed-lifetime `private_key_jwt` construction, and background refresh using an injected clock rather than wall-clock sleeps
- integration coverage for unauthenticated external calls under `auth.mode = none`, OAuth-protected calls succeeding end to end, expired tokens refreshing successfully, `401` causing one invalidate-and-replay cycle, submission and validation both succeeding under the same OAuth config, submission and validation producing the same business response even though access tokens and client assertions differ between runs, signing-key rotation taking effect on the next token acquisition, resource-server or token-endpoint certificate rotation taking effect after participant restart, and remote validation not sending a business `_health` call
- testing infrastructure must use an injectable clock for auth lifecycle tests and must mock token issuance through a dedicated token client or `MockOAuthServer`, not through the resource-server mock
