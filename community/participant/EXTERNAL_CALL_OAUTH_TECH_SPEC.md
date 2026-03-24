# External Call OAuth Tech Spec

## Purpose and Scope

This document defines OAuth-based service-to-service authentication for participant
`external_call` requests.

The goal is to let a participant call an external extension service using OAuth 2.0 client
credentials with `private_key_jwt`, without changing the Daml-level business protocol. After this
change, a Daml program still sends the same `(extensionId, functionId, configHash, input, mode)`
inputs through the existing participant extension seam, and the extension still returns either a
successful business response body or an error described by HTTP status, message, and request ID.

This spec is intentionally narrow. It adds OAuth inside the existing `external_call` runtime seam.
It does not introduce a general auth subsystem, a general transport abstraction, or a new startup
validation feature.

## Baseline Assumptions

- OAuth remains configured per extension.
- OAuth changes transport and authentication behavior only. It does not change Daml business
  semantics.
- The existing static bearer-token fields (`jwt` and `jwtFile`) can be replaced rather than carried
  indefinitely for compatibility.
- Sender-constrained mechanisms such as mTLS-bound access tokens are out of scope.
- Background token refresh is out of scope.
- Startup validation and participant startup gating are out of scope for OAuth v1.

## Determinism Requirement

For a fixed `(extensionId, functionId, configHash, input)`, successful business responses must be
identical in `submission` and `validation`.

Rules:

- `mode` remains forwarded unchanged on the existing wire contract.
- Access-token claims, client-assertion timestamps, `jti`, token expiry bookkeeping, and OAuth
  client identity are transport concerns only. They must not become business inputs.
- OAuth failures must surface only as external-call transport errors through the existing error
  boundary.

## Current Code Seam

The current participant extension flow is:

1. `community/participant/src/main/scala/com/digitalasset/canton/participant/ParticipantNode.scala`
   creates one `ExtensionServiceManager` when `parameters.engine.extensions` is non-empty.
2. `community/participant/src/main/scala/com/digitalasset/canton/participant/extension/ExtensionServiceManager.scala`
   creates one `HttpExtensionServiceClient` per configured extension.
3. `community/participant/src/main/scala/com/digitalasset/canton/participant/extension/ExtensionServiceExternalCallHandler.scala`
   forwards Daml engine external calls to the manager.
4. `community/participant/src/main/scala/com/digitalasset/canton/participant/extension/HttpExtensionServiceClient.scala`
   builds the HTTP request, injects authentication, performs retries, and maps failures into
   `ExtensionCallError`.

Current implementation constraints that the OAuth design must preserve:

- `ExtensionServiceExternalCallHandler` only exposes `statusCode`, `message`, and `requestId` to
  the Daml engine.
- `HttpExtensionServiceClient.callWithRetry` is the existing outer retry boundary.
- The existing resource-server protocol stays unchanged:
  - path: `/api/v1/external-call`
  - headers: `X-Daml-External-Function-Id`, `X-Daml-External-Config-Hash`,
    `X-Daml-External-Mode`, and the configured request ID header
  - request body and successful response body remain the existing hex-encoded business payloads
- The current retry classification stays in force for the outer loop:
  - terminal: `400`, `401`, `403`, `404`
  - retryable: `408`, `429`, `500`, `502`, `503`, `504`

## Target Runtime Design

### Ownership

OAuth stays inside `HttpExtensionServiceClient`. That class remains the sole owner of:

- request orchestration
- token acquisition
- token caching
- the existing outer retry loop
- the single OAuth-specific `401` refresh-and-replay
- request and error mapping

`ExtensionServiceManager` continues to create one client per configured extension and route calls by
extension ID. It does not become an auth provider, token manager, or shared transport layer.

Each `HttpExtensionServiceClient` owns the outbound HTTP client state for its extension. It may use
one or two Java `HttpClient` instances internally:

- one client for resource-server calls
- one client for token-endpoint calls when the token endpoint uses different TLS trust settings

If resource-server and token-endpoint TLS settings are identical, reusing a single client inside the
extension client is acceptable. Cross-extension sharing is not required and should not be assumed.

`ExtensionServiceExternalCallHandler` remains unchanged as a thin boundary mapper.

No background refresh task is introduced.

### Request Execution, Retries, and Deadlines

`HttpExtensionServiceClient` keeps the existing outer retry model. OAuth does not add a second outer
retry policy.

For one external-call operation, the client computes one absolute deadline from
`max-total-timeout` before the first outer attempt. The client never starts token acquisition or a
resource request once the remaining budget is non-positive.

One outer attempt is:

1. Compute the remaining total budget from the fixed absolute deadline.
2. Resolve authentication:
   - `auth.type = none`: no auth work is performed.
   - `auth.type = oauth`: obtain a valid access token against the same remaining budget.
3. Build the resource request for `/api/v1/external-call` with the existing
   `X-Daml-External-*` headers unchanged. When OAuth is enabled, add
   `Authorization: Bearer <token>`.
4. Apply the request timeout for that outbound request as
   `min(configured request-timeout, remaining budget)`.
5. Send the resource request.
6. If the resource response is not `401`, that response is the outcome of the outer attempt.
7. If the resource response is `401` and OAuth is enabled:
   - invalidate the cached token only if it is the same token that was attached to the rejected
     request
   - obtain a fresh token against the same outer deadline
   - replay the resource request once with the fresh token
8. Feed the replay result, or the original non-`401` result, back into the existing outer retry
   loop.

Rules:

- `callWithRetry` remains the only business-request retry loop.
- `maxRetries` counts only outer retries.
- One outer attempt may include one initial resource request and at most one auth-local replay.
- The auth-local replay does not consume a `maxRetries` slot.
- `401` is the only resource response that triggers OAuth-specific recovery after a resource request
  has already been sent.
- After the auth-local replay, normal status handling resumes:
  - `200` succeeds
  - `400`, `401`, `403`, and `404` are terminal
  - `408`, `429`, `500`, `502`, `503`, and `504` remain outer-loop retryable outcomes
- `connect-timeout` is a fixed per-extension client setting used when constructing the internal
  HTTP client or clients.
- OAuth v1 clamps per-request `request-timeout` to the remaining total budget, but it does not
  require dynamic per-attempt connect-timeout clamping.

### Resource Request Details

The resource-server wire contract is unchanged.

The participant continues to:

- generate a request ID for each outbound HTTP interaction
- place that ID in the configured `request-id-header`
- send `functionId`, `configHash`, and `mode` through the existing `X-Daml-External-*` headers

The resource request body and successful response body remain the existing business payloads.
OAuth only adds the `Authorization` header when `auth.type = oauth`.

## Token Acquisition and Caching

### Cache Model

OAuth uses simple on-demand token caching.

Rules:

- A cached access token may be reused while the client still considers it unexpired according to
  the locally computed expiry instant.
- An expired cached token is replaced on the next business request that needs OAuth.
- A cached token rejected by the resource server with `401` is invalidated for one refresh-and-
  replay attempt.
- There is no proactive refresh and no background work.
- Synchronization of concurrent cache misses is an implementation detail, not part of the public
  contract.

### Token Request

Token acquisition uses OAuth 2.0 client credentials with `private_key_jwt`.

The token request uses:

- `grant_type = client_credentials`
- `client_assertion_type = urn:ietf:params:oauth:client-assertion-type:jwt-bearer`
- `client_assertion = <signed JWT>`
- optional `scope` when configured

No token-request `audience` field is supported in v1.

The participant treats access tokens as opaque bearer tokens. It does not parse or locally verify
access-token claims.

Required token response fields are:

- `access_token`
- `token_type`
- `expires_in`

Rules:

- `token_type` must be `Bearer`, matched case-insensitively.
- `expires_in` is used to compute the local expiry instant for cache reuse.
- Missing or malformed required fields are treated as a malformed token response.

### Client Assertion

The client assertion for `private_key_jwt` uses:

- signing algorithm: `RS256`
- optional `kid` when configured
- claims:
  - `iss = client-id`
  - `sub = client-id`
  - `aud = <token-endpoint URI>`
  - `iat = now`
  - `exp = now + 30s`
  - `jti = <fresh random identifier>`

Assertions are one-use only and are never logged or persisted.

Supported signing-key format for v1 is RSA DER / PKCS#8.

### Key and Trust Material

Key and trust material are loaded when the `HttpExtensionServiceClient` is constructed rather than
re-read on every token request.

Consequences:

- key rotation takes effect on participant restart
- trust-material changes take effect on participant restart
- hot reload of OAuth key material is out of scope

### Token-Endpoint Failures

Token-endpoint failures consume the same outer retry budget as resource-server failures. OAuth does
not introduce a second retry policy.

Handling rules:

- Token-endpoint HTTP responses preserve their HTTP status code when possible.
- Token-endpoint `408`, `429`, `500`, `502`, `503`, and `504` are outer-loop retryable because they
  feed into the existing status-based retry logic.
- Token-endpoint `400`, `401`, `403`, and `404` are terminal because they feed into the existing
  status-based terminal classification.
- Transient connect failures, request timeouts, and I/O failures are mapped into the same status
  families already used by `HttpExtensionServiceClient` for resource-server calls.
- Malformed token responses map to `502`.
- Local signing, key-loading, and local auth-material failures map to `500`.

## Configuration

### Design Goals

The configuration change must stay close to the current
`community/participant/src/main/scala/com/digitalasset/canton/participant/config/ExtensionServiceConfig.scala`
shape. OAuth adds an auth block, not a general transport refactor.

### Resource-Server Configuration

The resource-server fields stay broadly flat on `ExtensionServiceConfig`.

The top-level per-extension config continues to carry:

- `name`
- `host`
- `port`
- `use-tls`
- optional `trust-collection-file` for the resource server
- `connect-timeout`
- `request-timeout`
- `max-total-timeout`
- `max-retries`
- `retry-initial-delay`
- `retry-max-delay`
- `request-id-header`
- `declared-functions`

The legacy static token fields `jwt` and `jwtFile` are replaced by a typed auth config.

### Auth Configuration

OAuth v1 uses a typed auth variant rather than `auth.mode` plus an optional nested OAuth block.

Conceptually:

- `auth.type = none`
- `auth.type = oauth`

`auth.type = none` means no `Authorization` header is sent.

`auth.type = oauth` contains:

- `token-endpoint`
  - `host`
  - `port`
  - `path`
  - optional `trust-collection-file`
- `client-id`
- `private-key-file`
- optional `key-id`
- optional `scope`

Rules:

- When `auth.type = oauth`, the resource server must use TLS.
- When `auth.type = oauth`, the token endpoint must use TLS.
- The existing insecure / trust-all TLS support remains test-only scaffolding and is not part of the
  supported OAuth contract.
- The token endpoint path must start with `/` and must not contain a query string or fragment.
- The token-endpoint URI is used both as the HTTP target and as the `aud` claim in the client
  assertion.
- A separate assertion-audience override is out of scope.

### Global Extension Settings

OAuth v1 does not change the role of `EngineExtensionsConfig`.

Rules:

- `echoMode` continues to short-circuit HTTP calls.
- Existing validation-related settings are left untouched by this spec, but OAuth v1 does not
  define new startup validation behavior around them.

### Example Config

```hocon
extensions = {
  test-ext = {
    name = "test-ext"

    host = "ext.example.internal"
    port = 443
    use-tls = true
    trust-collection-file = "/etc/canton/ext-ca.pem"

    auth = {
      type = oauth

      token-endpoint = {
        host = "issuer.example.internal"
        port = 443
        path = "/oauth2/token"
        trust-collection-file = "/etc/canton/issuer-ca.pem"
      }

      client-id = "participant1"
      private-key-file = "/etc/canton/oauth-client-key.der"
      key-id = "participant1-key"
      scope = "external.call.invoke"
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

## Error Handling

The OAuth feature keeps the existing error boundary shape.

Rules:

- Internal OAuth failures are mapped directly to `ExtensionCallError`.
- `ExtensionServiceExternalCallHandler` continues to expose only `statusCode`, `message`, and
  `requestId`.
- Resource-server transport and application failures keep the existing status and message mapping
  already used by `HttpExtensionServiceClient`.

Mapping rules for OAuth-specific failures:

- Token-endpoint HTTP failures preserve their HTTP status code when possible.
- Malformed token responses map to `502`.
- Local signing, key-loading, and local auth-material failures map to `500`.
- After the auth-local replay is exhausted, a resource-server token rejection maps to `401` with
  message `Unauthorized - OAuth token rejected by resource server`.

Request ID rule:

- Return the request ID of the outbound HTTP interaction that produced the returned error.
- If token acquisition fails before any HTTP request is sent, return `None`.

## Observability

Logging should be added for:

- token acquisition start
- token acquisition success and failure
- cache reuse versus token reacquisition
- token invalidation after resource-server `401`
- final external-call failure classification

Sensitive material must never be logged:

- access tokens
- client assertions
- private key material
- token-endpoint request bodies

OAuth-specific metrics are optional. The feature does not depend on adding new metrics.

## Testing

OAuth changes runtime request flow, so the tests must cover both unit behavior and end-to-end
integration behavior.

### Unit Tests

Add or update unit tests around:

- auth config parsing for typed auth variants
- token request construction
- client assertion construction
- token acquisition success and failure
- cache reuse
- expiry-driven reacquisition
- `401` invalidate-and-replay
- key-loading and trust-material failures

Whenever a bug is found during implementation, add a regression unit test for the failing scenario.

### Integration Tests

Reuse the existing external-call integration harness:

- `community/app/src/test/scala/com/digitalasset/canton/integration/tests/externalcall/ExternalCallIntegrationTestBase.scala`
- `community/app/src/test/scala/com/digitalasset/canton/integration/tests/externalcall/MockExternalCallServer.scala`

The existing `MockExternalCallServer` should be extended so it can serve both:

- the resource-server path `/api/v1/external-call`
- the configured token-endpoint path used by OAuth tests

Integration coverage must include:

- `auth.type = none` still using the existing non-OAuth behavior
- end-to-end OAuth success
- cached-token reuse across multiple business requests
- expiry-driven reacquisition on the next request
- single `401` refresh-and-replay
- submission and validation producing the same successful business response under OAuth

The existing non-OAuth `401` behavior should remain covered separately. OAuth-specific replay is an
additional path, not a global redefinition of all `401` handling.

## Out of Scope

The following are explicitly out of scope for OAuth v1:

- startup validation integration and participant startup gating
- local-only startup validation as a required behavior
- token-request `audience`
- proactive refresh or background refresh tasks
- sender-constrained tokens or mTLS-bound access tokens
- generic auth-provider interfaces
- a broad transport-config refactor for participant extensions
- hot reload of key material or trust material
