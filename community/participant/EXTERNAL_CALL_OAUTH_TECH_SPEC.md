# External Call OAuth Tech Spec

## Purpose and Baseline

This document defines the target design for adding OAuth-based service-to-service authentication to participant external calls. It is organized so each normative rule has one home: runtime behavior, configuration and startup validation, boundary error mapping, and observability are each specified once.

The goal is to add OAuth without changing the Daml external-call business protocol.

This spec assumes:

- the canonical production contract is `auth.mode = oauth` with `private_key_jwt` client authentication over TLS
- OAuth changes transport and authentication behavior only; it must not change Daml-level business semantics
- OAuth configuration remains per extension
- outbound OAuth may reuse audience and scope semantics already present elsewhere in Canton auth, but it does not reference named identity-provider definitions directly
- there are no existing external-call users to preserve, so the final config may replace the current static-token shape directly and needs no compatibility alias layer
- sender-constrained mechanisms such as mTLS are out of scope

Determinism requirement:

- for a fixed `(extensionId, functionId, configHash, input)`, successful business responses must be identical in `submission` and `validation`, and must not depend on access-token claims, client-assertion timestamps, `jti`, or OAuth client identity
- `mode` remains part of the existing external-call wire contract and is forwarded unchanged, but it must not select a different successful business response
- OAuth decides whether the participant may reach the service; once authorized, it must not behave as an extra business input

Current integration point:

1. `ParticipantNode` creates one `ExtensionServiceManager` when `parameters.engine.extensions` is non-empty.
2. `ExtensionServiceManager` creates one `HttpExtensionServiceClient` per configured extension.
3. `ExtensionServiceExternalCallHandler` forwards Daml engine calls to the manager.
4. `HttpExtensionServiceClient` builds the HTTP request, injects the current auth header, performs the transport call, classifies the response, and owns request retry logic.

Current implementation constraints:

- `ExtensionServiceConfig` currently mixes resource-server transport, auth material, and transport lifecycle settings in one case class
- `HttpExtensionServiceClient` currently reads a literal token from `jwt` or `jwtFile`, injects `Authorization: Bearer <token>` on every request, treats `401` as terminal, and has no token expiry, refresh, invalidation, or concurrent acquisition control
- `HttpExtensionServiceClient.callWithRetry` is already the correct outer retry boundary and must remain so; retryable outcomes are `408`, `429`, `500`, `502`, `503`, and `504`, while terminal outcomes are `400`, `401`, `403`, and `404`
- existing Canton pieces are reusable as patterns, not as drop-in types: JWT audience and scope semantics in `AuthServiceConfig` and `AuthServiceJWT`, JWKS retrieval and caching in `CachedJwtVerifierLoader`, token lifecycle semantics in `AuthenticationTokenManager`, JWT signing helpers in `com.daml.jwt.JwtSigner` and `KeyUtils`, and TLS trust configuration semantics in existing `TlsClientConfig` usage
- `AuthenticationTokenManager` cannot be reused literally for OAuth access tokens because it is typed around sequencer `AuthenticationToken`, which is a fixed-length binary token with a gRPC-oriented error surface; the lifecycle pattern is reusable, but the concrete types are not

## Runtime Behavior

### Auth Boundary and Ownership

Keep `HttpExtensionServiceClient` as the sole owner of request orchestration, deadlines, retry integration, and the single auth-local `401` replay. OAuth support lives behind a small helper boundary rather than a general auth-provider interface. The helpers are used only when `auth.mode = oauth`; `auth.mode = none` remains a straight-through request with no auth-specific objects.

The design introduces `OAuthAccessTokenManager`, `OAuthTokenClient`, and `PrivateKeyJwtConfig`.

Component responsibilities:

- `HttpExtensionServiceClient` resolves `auth.mode`, asks `OAuthAccessTokenManager` for a token when OAuth is enabled, injects `Authorization: Bearer <token>`, decides whether a resource-server `401` triggers one invalidate-and-replay cycle, and feeds the final outer-attempt outcome back into the existing retry loop
- `OAuthAccessTokenManager` owns cached token state, shared in-flight acquisition, expiry checks, and token-conditional invalidation
- `OAuthTokenClient` talks only to the configured token endpoint, builds and sends the `client_credentials` request with `private_key_jwt`, parses the token response, and classifies token-endpoint failures
- no auth-mode-polymorphic hot-path interface is introduced; `auth.mode = none` is handled as the absence of OAuth work rather than via a `NoAuthProvider`

Rules:

- foreground token acquisition happens inside `HttpExtensionServiceClient` before the first resource-server request of the current outer attempt
- `HttpExtensionServiceClient` passes the absolute outer deadline into foreground token acquisition and token-endpoint work must be clamped to that deadline
- when OAuth is enabled, the rejected token tracked for conditional invalidation is the exact token attached to the outgoing `Authorization` header
- request ids in this design are participant-generated outbound correlation ids; servers may echo them, but the protocol does not depend on a response-header request-id contract

Lifecycle ownership:

- `ParticipantNode` passes its existing clock into `ExtensionServiceManager` so OAuth helpers can use it for token-expiry comparisons
- `ExtensionServiceManager` owns extension clients; when OAuth is enabled for an extension, the corresponding `HttpExtensionServiceClient` owns the `OAuthAccessTokenManager` and `OAuthTokenClient` for that extension
- no long-lived auth-side background task is introduced in the first implementation
- resource-server and token-endpoint clients are built for the TLS and auth configuration they actually use; the design must not depend on one globally shared `HttpClient` across distinct TLS and auth cases
- constructing `ExtensionServiceManager`, `HttpExtensionServiceClient`, and lightweight OAuth config wrappers must not perform fallible key loading, trust-material loading, or TLS-context construction; those failures must surface through explicit startup validation or structured runtime call failures rather than escaping construction

### Request Execution, Retries, and Deadlines

`HttpExtensionServiceClient` keeps the existing resource-server protocol: endpoint shape `/api/v1/external-call`, `X-Daml-External-*` headers, participant-generated request ids, transport execution, response classification, and the outer retry budget. It stops owning token file loading, token caching and expiry handling, token invalidation policy, and token-endpoint request construction.

For one external-call operation, `HttpExtensionServiceClient` computes one absolute deadline from `max-total-timeout` before the first outer attempt. Every outer attempt uses the remaining budget against that fixed deadline.

One outer attempt is:

1. Compute the remaining budget against the operation deadline.
2. If `auth.mode = none`, skip auth work. If `auth.mode = oauth`, ask `OAuthAccessTokenManager` for a token for that deadline.
3. Send the request to `/api/v1/external-call` with the existing `X-Daml-External-*` headers unchanged, and add `Authorization: Bearer <token>` when OAuth is enabled. Connect and request timeouts are clamped to the remaining budget.
4. If the response is not `401`, or if `auth.mode = none`, that response is the outcome of the outer attempt.
5. If the response is `401` under `auth.mode = oauth`, invalidate cached OAuth state only if the rejected token still matches the current cached token.
6. Acquire a fresh token against the same outer deadline.
7. If fresh token acquisition fails, fail the outer attempt with that auth failure.
8. Otherwise replay the resource request once with the fresh token.
9. Treat the replay result, or the original non-`401` response, as the outcome of the outer attempt and feed that outcome back into the existing retry loop.

Runtime rules:

- the outer retry loop remains the only business-request retry loop
- `maxRetries` counts only completed outer retries
- an outer attempt may include one initial resource request and at most one auth-local replay
- the auth-local replay does not consume a `maxRetries` slot
- any retryable result produced after the replay is the final result of that outer attempt
- if the outer loop retries after that result, that consumes one `maxRetries` slot
- `401` is the only resource response that may trigger auth-specific recovery after a resource request has been sent
- invalidate cached OAuth state on `401` only if the rejected token still matches the token manager's current token
- do not invalidate on `403`, `404`, `429`, `5xx`, timeouts, or transport failures
- record `WWW-Authenticate` when present for diagnostics, but do not depend on it for invalidation
- after the single auth-local replay, `200` succeeds; `401`, `400`, `403`, and `404` are terminal; `408`, `429`, `500`, `502`, `503`, and `504` remain retryable outer-attempt outcomes; transport failures mapped to retryable statuses behave the same way
- token-acquisition failures are classified from the structured auth failure, not from a later flattened HTTP status code
- if no positive deadline budget remains, neither token acquisition nor a resource request is started

The outer retry loop remains owned by `HttpExtensionServiceClient`; the spec does not require a new auth-provider-driven async orchestration layer. If the implementation keeps the current blocking retry loop shape, token-endpoint work and the auth-local replay must still honor the same absolute operation deadline.

### OAuth Token Lifecycle

Add an OAuth-specific access-token manager that reuses only the useful parts of the `AuthenticationTokenManager` pattern: lazy first acquisition, cached expiry-aware state, and shared in-flight acquisition. The first implementation does not reuse `AuthenticationTokenManagerConfig`, does not schedule background refresh, and does not introduce a second operator-facing retry policy.

Concrete token type: `OAuthAccessTokenWithExpiry(accessToken: String, expiresAt: CantonTimestamp, tokenType: String)`.

`OAuthAccessTokenManager` provides lazy first acquisition, a cached token with expiry, one shared in-flight foreground acquisition, and explicit invalidation. It performs no background work. When a business request needs auth, the manager returns the cached token if it is still valid; otherwise it starts one foreground token acquisition bounded by the caller's remaining outer deadline. If that acquisition fails, the current outer attempt fails and the existing outer retry loop decides whether to retry. Expiry is handled on demand: an expired cached token is discarded and the next business request acquires a fresh token.

Shared in-flight rules:

- one foreground fetch is shared; later callers wait on that future instead of starting a second token-endpoint request
- each waiting business request still enforces its own outer deadline while waiting
- if one waiting caller times out, it fails locally without cancelling the shared fetch
- a shared fetch that later succeeds populates the cache for later callers

`OAuthTokenClient` is a dedicated HTTP client for the token endpoint. It talks only to the configured token endpoint, uses its own TLS settings, generates a participant-local request id for each token-endpoint interaction and sends it in the configured request-id header, receives an absolute deadline for foreground acquisition, returns `OAuthAccessTokenWithExpiry`, and never logs secret-bearing inputs or outputs.

Token-endpoint failure classification:

- this retryability matrix is specific to external-call OAuth and does not inherit the gRPC exception policy used elsewhere
- retryable failures are HTTP `408`, `429`, `500`, `502`, `503`, and `504`, plus connect timeout, request timeout, and transient connect or I/O failure before an HTTP response
- fatal failures are HTTP `400`, `401`, `403`, `404`, any other `4xx`, TLS trust or certificate failure, TLS hostname-verification failure, malformed token response, unsupported `token_type`, client-assertion signing failure, and local auth-material or key-loading failure
- there is no separate auth-local retry loop for token acquisition; retryable token-endpoint failures are returned to the outer retry loop as the outcome of the current outer attempt
- during foreground acquisition, the token-endpoint HTTP attempt itself must fit within the caller's remaining outer deadline
- if a retryable token-endpoint failure includes `Retry-After`, that hint may be used by the outer retry loop when scheduling the next outer attempt

Token request and response rules:

- the request uses `grant_type = client_credentials`; optional `scope` and `audience` are sent as token-request fields when present
- required response fields are `access_token`, `token_type`, and `expires_in`; `token_type` must be `Bearer`, matched case-insensitively
- responses missing those fields or providing unusable expiry metadata are rejected
- access tokens are treated as opaque bearer tokens plus expiry metadata; the participant does not parse or locally verify the access token and sends `Authorization: Bearer <access_token>` to the resource server
- the token endpoint validates the `private_key_jwt` client assertion, the resource server validates the access token, and the participant validates response shape, token type, expiry metadata, and auth-failure signals

Client authentication and key-handling rules:

- the first implementation supports one production client-auth mechanism: `private_key_jwt`
- the token request includes `client_assertion_type = urn:ietf:params:oauth:client-assertion-type:jwt-bearer` and `client_assertion = <signed JWT>`
- the JWT header uses `alg = RS256`; `key-id` is optional and is emitted as `kid` when present
- JWT claims are `iss = client-id`, `sub = client-id`, `aud = <canonical token-endpoint URI>`, `iat = now`, `exp = now + 30s`, and `jti = <fresh random identifier>`
- the participant never reuses a previously signed client assertion
- RSA DER/PKCS8 is the only supported signing-key format
- private keys are re-read whenever a new client assertion is produced, so key rotation takes effect on the next token acquisition
- assertion lifetime is fixed and short in the first implementation rather than becoming a new operator-facing knob
- signed assertions, key material, and key-file contents are never logged or persisted

Accepted tradeoff: issued access tokens are still bearer tokens and remain replayable until expiry if exfiltrated; this is accepted in exchange for a simpler and more canonical external-call auth contract.

## Configuration

### Config Model

`ExtensionServiceConfig` is split into explicit blocks for the resource-server endpoint, auth mode and auth-specific settings, business-request transport lifecycle, and declared functions. The final config reuses Canton endpoint vocabulary rather than inventing a second transport dialect.

Each resource-server and token-endpoint block uses `address`, `port`, and `tls`.

Implementation rules:

- `external_call` introduces its own endpoint ADTs under participant config; it does not literally embed `FullClientConfig`
- those ADTs reuse the existing field vocabulary and TLS semantics
- `keepAliveClient` is not part of the `external_call` contract
- PureConfig readers and writers remain local to the new `ExtensionServiceConfig`-specific case classes

Global extension settings:

- `EngineExtensionsConfig` owns extension-wide startup-validation policy
- `validation-mode` lives under those global settings and replaces the current pair of startup-validation booleans
- `echoMode` remains a test-only knob

Auth and endpoint rules:

- supported auth modes are `none` and `oauth`
- `auth.mode = oauth` is the canonical production path
- when OAuth is enabled, TLS is required for both the resource server and the token endpoint
- plaintext `http` endpoints are rejected during config validation
- any insecure or trust-all hook remains test-only scaffolding and is not part of the supported OAuth contract

The token-endpoint config derives one canonical HTTPS URI string. Scheme is always `https`. Host is the configured `address`, copied exactly. Port is omitted when it is `443`; otherwise it is included as `:<port>`. Path is the configured `path`, copied exactly. The path must start with `/` and must not contain a query string or fragment. No dot-segment normalization, trailing-slash rewriting, or host rewriting is performed. That canonical URI is used both as the actual token-endpoint request target and as the `aud` claim in the `private_key_jwt` client assertion. This design does not introduce a separate client-assertion audience field.

Transport ownership:

- business-request transport settings remain top-level extension settings: `connect-timeout`, `request-timeout`, `max-total-timeout`, `max-retries`, `retry-initial-delay`, `retry-max-delay`, and `request-id-header`
- `connect-timeout` and `request-timeout` are the shared per-attempt HTTP timeout settings for both resource-server calls and token-endpoint calls
- during a foreground attempt, the effective connect timeout is `min(connect-timeout, remaining max-total-timeout)` and the effective request timeout is `min(request-timeout, remaining max-total-timeout)`
- `max-total-timeout` is the outer budget for one business external-call operation, including foreground token acquisition and one allowed `401` replay
- `max-retries`, `retry-initial-delay`, and `retry-max-delay` are owned only by the outer business-request retry loop in `HttpExtensionServiceClient`
- there is no separate `auth.oauth.token-manager` retry block in the first implementation; retryable token-endpoint failures consume the same outer retry budget as retryable resource-server failures

Rotation application points:

- signing keys are re-read when a new client assertion is produced
- token-endpoint and resource-server trust material is loaded during explicit local validation and again when the corresponding runtime HTTP client is built
- replacing trust material therefore takes effect on participant restart, not via hot reload

### Example Config

```hocon
extension-settings = {
  validation-mode = strict-remote
}

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

## Startup Validation Contract

Startup validation is an explicit participant-startup gate. Except in `echoMode`, `ParticipantNode` must await `ExtensionServiceManager.validateAllExtensions()` before enabling any runtime path that can execute or re-execute external calls, including synchronizer-side reinterpretation and confirmation, and before exposing Ledger API services.

`validation-mode = off | local | best-effort-remote | strict-remote`

Replace `ExtensionValidationResult.Valid | Invalid(errors)` with a structured per-extension report:

```scala
final case class ExtensionValidationReport(
  extensionId: String,
  localErrors: Seq[String],
  remoteErrors: Seq[String],
  remoteWarnings: Seq[String],
)
```

Contract:

- `ExtensionServiceClient.validateConfiguration(validationMode)` returns `ExtensionValidationReport`
- `ExtensionServiceManager.validateAllExtensions()` returns `Map[String, ExtensionValidationReport]`
- `ExtensionServiceManager` reports per configured `extensionId`; `ParticipantNode` interprets the aggregate against `validation-mode`
- `off` skips startup validation and produces no report
- in all other modes, validation is independent per configured `extensionId`: local validation runs first, and remote validation for that `extensionId` runs only if local validation succeeded
- local validation covers malformed or inconsistent config, unreadable keys or trust material, and invalid TLS material
- `local` fails startup on any `localErrors`
- `best-effort-remote` runs remote validation where applicable, fails startup on `localErrors`, and downgrades `remoteErrors` to warnings when interpreting results
- `strict-remote` runs the same validation work as `best-effort-remote` and fails startup on any `localErrors` or `remoteErrors`
- `remoteWarnings` never block startup; clients always report remote validation failures in `remoteErrors`
- `auth.mode = none` remote validation performs only the resource-server transport probe; `auth.mode = oauth` also performs real token acquisition
- remote validation must not send a business request through `/api/v1/external-call`: token-endpoint validation performs a real token acquisition, and resource-server validation uses a dedicated transport-validation helper that performs only DNS resolution, TCP connect, and, when TLS is enabled, SSL/TLS handshake using the same trust material as the runtime client, without sending an HTTP method, path, body, or headers
- in `echoMode`, no HTTP clients, OAuth token managers, or remote probes are constructed; `off` returns no report and other modes return one empty-success report per configured `extensionId` with empty `localErrors`, `remoteErrors`, and `remoteWarnings`

## Error Model and Boundary Mapping

Internally, the participant distinguishes token acquisition failure, token rejection by the resource server, resource-server transport failure, and resource-server application error. The internal error ADT stays structured and is flattened to the current `ExternalCallError` shape only at the boundary to the Daml engine. Logs, metrics, and retry decisions use the structured classes.

`ExternalCallAuthFailure` carries a message and an optional token-endpoint request id. Concrete cases:

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

- `TokenEndpointHttpFailure` preserves the HTTP status code
- `TokenEndpointTimeout` maps to `408`
- `TokenEndpointIoFailure` maps to `503`
- `TokenEndpointTlsFailure` maps to `503`
- `ClientAssertionSigningFailure` maps to `500`
- `LocalAuthMaterialFailure` maps to `500`
- `UnsupportedTokenType` maps to `502`
- `MalformedTokenResponse` maps to `502`
- every token-acquisition failure message is prefixed with `OAuth token acquisition failed:`
- token rejection by the resource server maps to `401` after auth-local replay is exhausted and uses the message `Unauthorized - OAuth token rejected by resource server`
- resource-server transport failures preserve the current transport-derived status mapping and messages from `HttpExtensionServiceClient`
- resource-server application errors preserve the resource server's HTTP status code and the current body-to-message mapping from `HttpExtensionServiceClient`

Boundary request-id rule:

- the boundary `requestId` is the participant-generated outbound correlation id from the last HTTP interaction that determined the final failure for the outer attempt
- `ExternalCallAuthFailure.requestId` is the participant-generated token-endpoint request id when token-endpoint HTTP work had already started
- if the final failure happens before any resource request is sent, return `authFailure.requestId` when present, otherwise `None`
- if a resource response ends the attempt without an auth-local replay, return that resource request id
- if a `401` triggers token acquisition and a replay request is sent, the replay request id supersedes both the original resource request id and the token-endpoint request id
- if token acquisition after a `401` fails before a replay request is sent, return `authFailure.requestId` when present, otherwise the original `401` resource request id

## Observability and Testing

Logging:

- add structured logs for token acquisition start, success, and failure; cache hit and expired-token miss; token invalidation; auth rejection on `401`; and final external-call failure classification
- safe log dimensions are extension id, auth mode, audience, scope, status code, and request id
- never log access tokens, client assertions, client secrets, private key material, or other secret-bearing token-endpoint fields

Metrics:

- add participant metrics under a new external-call subtree
- include token acquisition success count, token acquisition failure count, token invalidation count, token cache hit and miss count, and an auth latency timer

Tests:

- unit coverage for auth config parsing and exclusivity, OAuth token acquisition success and failure, concurrent callers sharing one token acquisition, expired cached tokens forcing on-demand reacquisition, token-conditional invalidation on `401`, audience and scope propagation, private-key and certificate loading failures, deadline composition between auth and business retries, and fixed-lifetime `private_key_jwt` construction
- integration coverage for unauthenticated external calls under `auth.mode = none`, OAuth-protected calls succeeding end to end, expired cached tokens causing the next call to obtain a fresh token successfully, `401` causing one invalidate-and-replay cycle, submission and validation both succeeding under the same OAuth config, submission and validation producing the same business response even though access tokens and client assertions differ between runs, signing-key rotation taking effect on the next token acquisition, resource-server or token-endpoint certificate rotation taking effect after participant restart, and remote validation not sending a business `_health` call
- testing infrastructure must use an injectable clock for token-expiry tests and must mock token issuance through a dedicated token client or `MockOAuthServer`, not through the resource-server mock
