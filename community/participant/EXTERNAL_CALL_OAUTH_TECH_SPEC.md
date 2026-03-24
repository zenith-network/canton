# External Call OAuth Tech Spec

## Status

Draft for iterative refinement.

This document turns the requirements in `community/participant/EXTERNAL_CALL_OAUTH_REQUIREMENTS.md` into a codebase-grounded design for the current participant implementation.

## Scope

Add OAuth-based service-to-service authentication to participant external calls without changing the Daml external-call business protocol.

This spec is intentionally grounded in the current implementation:

- `community/participant/src/main/scala/com/digitalasset/canton/participant/config/ExtensionServiceConfig.scala`
- `community/participant/src/main/scala/com/digitalasset/canton/participant/extension/ExtensionServiceManager.scala`
- `community/participant/src/main/scala/com/digitalasset/canton/participant/extension/HttpExtensionServiceClient.scala`
- `community/base/src/main/scala/com/digitalasset/canton/sequencing/authentication/AuthenticationTokenProvider.scala`
- `community/base/src/main/scala/com/digitalasset/canton/sequencing/authentication/grpc/AuthenticationTokenManager.scala`
- `community/base/src/main/scala/com/digitalasset/canton/config/AuthServiceConfig.scala`
- `community/base/src/main/scala/com/digitalasset/canton/auth/AuthServiceJWT.scala`
- `community/base/src/main/scala/com/digitalasset/canton/auth/CachedJwtVerifierLoader.scala`

## Current Codebase Snapshot

### Current external-call path

The current external-call stack is:

1. `ParticipantNode` creates one `ExtensionServiceManager` when `parameters.engine.extensions` is non-empty.
2. `ExtensionServiceManager` creates one `HttpExtensionServiceClient` per configured extension.
3. `ExtensionServiceExternalCallHandler` forwards Daml engine calls to the manager.
4. `HttpExtensionServiceClient` builds the HTTP request, injects the current auth header, performs the transport call, classifies the response, and owns request retry logic.

### Current config shape

`ExtensionServiceConfig` currently mixes three concerns in one case class:

- resource server address and TLS toggles: `host`, `port`, `useTls`, `tlsInsecure`
- auth material: `jwt`, `jwtFile`
- transport lifecycle: `connectTimeout`, `requestTimeout`, `maxTotalTimeout`, `maxRetries`, retry delays

This makes OAuth awkward because token acquisition introduces:

- a second HTTP destination
- auth modes beyond a literal bearer token
- token lifecycle state that should not live in `HttpExtensionServiceClient`

### Current auth behavior

`HttpExtensionServiceClient` currently:

- lazily reads a literal token from `jwt` or `jwtFile`
- injects `Authorization: Bearer <token>` on every request
- treats `401` as a non-retryable terminal error
- has no token expiry, refresh, invalidation, or concurrent acquisition control

### Current retry boundary

`HttpExtensionServiceClient.callWithRetry` owns transport retry today:

- retryable: `408`, `429`, `500`, `502`, `503`, `504`
- non-retryable: `400`, `401`, `403`, `404`
- bounded by `maxTotalTimeout`

This is the correct outer retry boundary to preserve. OAuth token acquisition must fit inside it rather than create an unbounded second loop.

### Existing reusable auth patterns

The current codebase already has the pieces this design should align with:

- JWT audience and scope semantics in `AuthServiceConfig` and `AuthServiceJWT`
- JWKS retrieval and caching in `CachedJwtVerifierLoader`
- token lifecycle semantics in `AuthenticationTokenManager`
- token acquisition retry/backoff settings in `AuthenticationTokenManagerConfig`
- JWT signing helpers in `com.daml.jwt.JwtSigner` and `KeyUtils`
- TLS trust configuration semantics through existing `TlsClientConfig` usage in `ClientChannelBuilder`

### Important constraint from current code

`AuthenticationTokenManager` cannot be reused literally as-is for OAuth access tokens:

- it is typed around sequencer `AuthenticationToken`
- `AuthenticationToken` is a fixed-length binary token, not an arbitrary OAuth bearer token or JWT
- the manager's error surface is `io.grpc.Status`

The lifecycle pattern is reusable. The concrete types are not.

## Design Summary

The participant external-call stack should be refactored so that:

1. `ExtensionServiceConfig` exposes an explicit auth mode.
2. `HttpExtensionServiceClient` owns only business-request transport and outer retry.
3. A new auth abstraction owns token acquisition, caching, refresh, invalidation, and auth-aware validation.
4. OAuth token acquisition uses the same operational model as `AuthenticationTokenManager`, but with an external-call-specific access-token type and HTTP token client.
5. The final config reuses existing Canton client-config vocabulary (`address`, `port`, `tls`) for both the resource server and the token endpoint rather than introducing a second transport dialect.
6. The final state uses one canonical production auth contract: OAuth with `private_key_jwt` client authentication over standard TLS.

## Proposed Runtime Architecture

### New participant-side auth boundary

Add a new package under `community/participant/src/main/scala/com/digitalasset/canton/participant/extension/auth/` with the following responsibilities:

- resolve auth config into a concrete auth strategy
- decorate outbound business requests with auth material
- invalidate cached auth state on explicit auth rejection
- validate local auth configuration and remote auth reachability according to the global validation mode
- own auth-side lifecycle resources so they can be closed together with the extension manager

Validation reporting stays under `community/participant/src/main/scala/com/digitalasset/canton/participant/extension/` because it is an extension-manager boundary type, not an auth-internal type.

Proposed logical types:

- `ExternalCallAuthConfig`
- `ExternalCallAuthProvider`
- `NoAuthProvider`
- `OAuthExternalCallAuthProvider`
- `OAuthAccessTokenManager`
- `OAuthTokenClient`
- `PrivateKeyJwtConfig`

The important design point is the boundary, not the exact class names.

Concrete auth-provider contract:

- `prepareRequest(`
- `  deadline: CantonTimestamp`
- `)(implicit tc: TraceContext): FutureUnlessShutdown[Either[AuthPreparationFailure, PreparedAuth]]`
- `handleResponse(`
- `  responseContext: AuthResponseContext,`
- `  preparedAuth: PreparedAuth,`
- `  deadline: CantonTimestamp`
- `)(implicit tc: TraceContext): FutureUnlessShutdown[AuthResponseDecision]`

Concrete supporting types:

- `final case class PreparedAuth(`
- `  authorizationHeader: Option[String],`
- `  tokenUsed: Option[String],`
- `  tokenEndpointRequestId: Option[String],`
- `)`
- `final case class AuthResponseContext(`
- `  statusCode: Int,`
- `  resourceRequestId: String,`
- `  wwwAuthenticate: Option[String],`
- `)`
- `sealed trait AuthResponseDecision`
- `case object NoReplay extends AuthResponseDecision`
- `final case class ReplayOnceWithFreshAuth(nextPreparedAuth: PreparedAuth) extends AuthResponseDecision`
- `final case class FailAuth(authFailure: AuthPreparationFailure) extends AuthResponseDecision`

Contract rules:

- `prepareRequest` performs any foreground token acquisition required for the current outer attempt
- `prepareRequest` receives the absolute outer deadline and is responsible for clamping token-endpoint work to that deadline
- `PreparedAuth.tokenUsed` is the exact token attached to the outgoing resource request and is the value used for token-conditional invalidation
- `PreparedAuth.tokenEndpointRequestId` records the last participant-generated token-endpoint request id involved in preparing that auth state for the current outer attempt
- `AuthResponseContext` carries the subset of resource-response metadata the auth layer is allowed to inspect
- `HttpExtensionServiceClient` generates the resource-server request id locally before send, places it in the configured request-id header, and copies that same participant-generated value into `AuthResponseContext.resourceRequestId`
- `HttpExtensionServiceClient` constructs `AuthResponseContext` from the concrete resource-server `HttpResponse` plus that locally generated resource request id before releasing the response object
- `AuthResponseContext.wwwAuthenticate` is populated from the first `WWW-Authenticate` response header when present
- request ids in this design are participant-generated outbound correlation ids; servers may echo them, but the protocol does not depend on any response-header request-id contract
- `handleResponse` is pure from the perspective of business retry ownership: it may request one auth-local replay, but it does not advance the outer retry counter
- `handleResponse` may return `ReplayOnceWithFreshAuth` at most once per outer attempt

### `HttpExtensionServiceClient` after refactor

`HttpExtensionServiceClient` keeps:

- endpoint shape `/api/v1/external-call`
- `X-Daml-External-*` headers
- request ID generation
- resource-server HTTP transport
- outer transport retry budget

It stops owning:

- token file loading
- token caching
- token refresh
- token invalidation policy
- client-auth-specific request construction for token acquisition

Instead, each business-request attempt should:

1. ask the auth provider to decorate the request using the remaining outer deadline
2. execute the resource-server request
3. let the auth provider inspect the response status and headers for invalidation
4. let the existing retry loop decide whether another business-request attempt is allowed

### Token lifecycle manager

Add an OAuth-specific access-token manager that reuses the `AuthenticationTokenManager` state machine and config semantics:

- lazy first acquisition
- shared in-flight acquisition
- cached token with expiry
- background refresh before expiry
- explicit invalidation
- retry/backoff during acquisition

Concrete shape:

- `OAuthAccessTokenWithExpiry(accessToken: String, expiresAt: CantonTimestamp, tokenType: String)`
- `AuthenticationTokenManagerConfig`

`OAuthAccessTokenManager` uses `AuthenticationTokenManagerConfig` directly. This design does not introduce an external-call-specific lifecycle config type.

Lifecycle ownership must also reuse the same control pattern:

- `ExtensionServiceManager` becomes the owner of auth providers and passes a `Clock` plus `isClosing` signal into `OAuthAccessTokenManager`
- `ExtensionServiceManager.onClosed()` must close auth providers so background refresh stops when the participant shuts down
- `ParticipantNode` must pass its existing clock into the manager instead of leaving auth refresh on wall-clock calls hidden inside the HTTP client
- `ParticipantNode` must register `extensionServiceManagerOpt.foreach(addCloseable)` or an equivalent lifecycle-managed close path so `ExtensionServiceManager.onClosed()` is guaranteed to run during participant shutdown

### Foreground and background acquisition rules

The token manager has two acquisition modes:

- foreground acquisition, triggered by a business request that needs a token
- background refresh, triggered proactively before expiry

Those modes do not share the same deadline source:

- foreground acquisition receives the remaining outer `maxTotalTimeout` budget from `HttpExtensionServiceClient`
- background refresh is not tied to any one business request and is bounded by token-manager retry settings plus per-attempt HTTP timeouts
- a failed background refresh clears the cached token, matching `AuthenticationTokenManager` semantics
- the next business request then performs foreground acquisition using the outer business-call deadline

Shared in-flight acquisition rule:

- `OAuthAccessTokenManager` preserves one shared in-flight acquisition or refresh, matching `AuthenticationTokenManager`
- if a business request arrives while that shared foreground or background fetch is already in flight, it waits on the existing shared future rather than starting a second token-endpoint fetch
- each waiting business request still enforces its own outer deadline while waiting on that shared future
- if a waiting business request reaches its deadline before the shared future completes, that business request fails locally with token-acquisition timeout
- the shared fetch continues running after that caller times out; if it later succeeds, it populates the token cache for later callers
- deadline expiry while waiting on a shared fetch never cancels the shared fetch and never causes a second parallel fetch to be started for the timed-out caller

### Token acquisition client

`OAuthTokenClient` is a small HTTP client that:

- talks only to the configured token endpoint
- uses its own TLS settings
- generates a participant-local correlation id for each token-endpoint HTTP interaction, sends it in the configured request-id header, and uses that same id for logging and error propagation
- receives an absolute deadline from the outer external-call attempt for foreground acquisition
- returns `OAuthAccessTokenWithExpiry`
- never logs secret-bearing inputs or outputs

Initial grant type:

- `client_credentials`

Token response requirements:

- `access_token`
- `token_type`
- `expires_in`

Any token response that omits one of those fields, or provides unusable expiry metadata, is rejected.

`token_type` must be `Bearer`, matched case-insensitively. Any other token type is rejected.

### Access-token handling

Returned OAuth access tokens are treated as opaque bearer tokens together with expiry metadata.

The participant does not parse or locally verify the returned access token.
The participant sends the access token to the resource server as `Authorization: Bearer <access_token>`.

Validation responsibility is split as follows:

- the token endpoint validates the `private_key_jwt` client assertion
- the resource server validates the access token
- the participant validates response shape, token type, expiry metadata, and HTTP auth-failure signals

Foreground timeout rule:

- every foreground HTTP connect attempt uses an effective connect timeout of `min(connect-timeout, remaining max-total-timeout budget)`
- every foreground token-endpoint HTTP attempt must clamp its timeout to `min(request-timeout, remaining max-total-timeout budget)`
- if no budget remains, token acquisition fails immediately and the business request is not sent
- if the effective connect timeout is non-positive, the connect attempt is not started

Background timeout rule:

- background HTTP connect attempts use `connect-timeout`
- background refresh reuses the same per-attempt `request-timeout` cap
- background refresh never borrows time from a business request and never extends a completed request path

## Proposed Config Model

### High-level direction

Keep auth nested under `ExtensionServiceConfig`, but split the config into explicit blocks:

- resource server transport
- auth mode
- request/retry settings
- declared functions

The final shape reuses existing Canton transport field names:

- `address`
- `port`
- `tls`

The only new HTTP-specific field that the token endpoint needs beyond those existing patterns is `path`.

Implementation rule:

- `external_call` introduces its own narrower endpoint ADTs under participant config; it does not literally embed `FullClientConfig`
- those endpoint ADTs reuse only the existing field names and TLS vocabulary: `address`, `port`, and `tls`
- `keepAliveClient` is not part of the `external_call` config contract
- PureConfig continues to derive readers and writers from the new `ExtensionServiceConfig`-local case classes rather than reusing `FullClientConfig` codecs
- the token-endpoint block uses the same endpoint field vocabulary plus `path`

### Token-endpoint URI derivation

The token-endpoint config derives one canonical HTTPS URI string.

Derivation rule:

- scheme is always `https`
- host is the configured `address`, copied exactly
- port is omitted when it is `443`
- port is included as `:<port>` when it is not `443`
- path is the configured `path`, copied exactly

Validation rule:

- `path` must start with `/`
- `path` must not contain a query string
- `path` must not contain a fragment
- no dot-segment normalization, trailing-slash rewriting, host lowercasing, or other URI rewriting is performed

Usage rule:

- the actual token-endpoint HTTP request target uses that canonical URI
- the `private_key_jwt` client assertion uses that same canonical URI as its `aud` claim
- this design does not define a separate client-assertion audience field distinct from the token-endpoint URI

### Transport lifecycle settings

The final config preserves the current transport-lifecycle knobs explicitly.

Business-request transport settings remain top-level extension settings:

- `connect-timeout`
- `request-timeout`
- `max-total-timeout`
- `max-retries`
- `retry-initial-delay`
- `retry-max-delay`

Ownership of those settings is:

- `connect-timeout`
  - remains a transport setting
  - applies to HTTP client connection establishment for both the resource server client and the token endpoint client
- `request-timeout`
  - remains a per-attempt HTTP timeout
  - applies to both resource-server requests and token-endpoint requests
- `max-total-timeout`
  - remains the outer budget for one business external-call operation
  - applies to the business request and any foreground token acquisition done on behalf of that request
- `max-retries`
  - remains the outer business-request retry limit owned by `HttpExtensionServiceClient`
- `retry-initial-delay`
  - remains the initial backoff delay for outer business-request retries
- `retry-max-delay`
  - remains the cap for outer business-request retry delay

Auth token lifecycle retries are configured separately under `auth.oauth.token-manager` through `AuthenticationTokenManagerConfig`:

- `refresh-auth-token-before-expiry`
- `retries`
- `min-retry-interval`
- optional exponential-backoff settings from `AuthenticationTokenManagerConfig`

This split is intentional:

- extension-level retry knobs govern replay of business calls to the resource server
- token-manager retry knobs govern acquisition and refresh of OAuth tokens
- no existing transport knob is silently removed or folded into a default

### Auth modes

Proposed auth modes:

- `none`
- `oauth`

Clarification:

- The canonical production contract is still `auth.mode = oauth`.
- Sender-constrained mechanisms such as mTLS are out of scope for this design.

### Final config shape

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

### Why this shape fits the current codebase

- It keeps auth under the per-extension config, which matches how `ExtensionServiceManager` already instantiates one client per extension.
- It keeps transport retry fields where `HttpExtensionServiceClient` already consumes them.
- It reuses existing Canton `address` / `port` / `tls` vocabulary instead of inventing a second transport shape.
- It keeps the auth subtree concrete: one `oauth` block, one supported client-auth mechanism, and one token-endpoint config shape.
- It creates room for token-endpoint TLS without overloading the resource-server `useTls` and `tlsInsecure` booleans.

### Resource-server TLS

The current `useTls` and `tlsInsecure` booleans are too weak for OAuth-enabled deployments because:

- they cannot express different trust roots for the resource server and token endpoint
- `ExtensionServiceManager` currently applies insecure TLS globally if any extension enables it

The design replaces them with existing `TlsClientConfig`-style semantics for both destinations rather than carrying forward the legacy external-call fields.

Fail-closed rule:

- `auth.mode = oauth` requires TLS on both the resource server and the token endpoint
- plaintext `http` token endpoints and resource endpoints are rejected during config validation
- any retained insecure/trust-all hook remains test-only implementation scaffolding and is not part of the supported OAuth config contract

Rotation application points:

- private signing keys are re-read when a new client assertion is produced
- TLS trust material for the token endpoint and resource server is loaded when the corresponding HTTP client is built
- replacing TLS trust material therefore takes effect on participant restart, not through in-process hot reload

## OAuth Client Authentication

### Final-state decision

The final-state design supports one production client-auth mechanism:

- `private_key_jwt`

This keeps the external-call auth contract canonical:

- production auth mode is `oauth`
- production client authentication is `private_key_jwt`
- transport protection is standard TLS

Sender-constrained mechanisms such as mTLS are explicitly out of scope for this design.

### `private_key_jwt`

This is the most direct fit with current Canton code:

- JWT signing helpers already exist via `JwtSigner`
- private key loading exists via `KeyUtils.readRSAPrivateKeyFromDer`
- lifetime and leeway semantics already exist elsewhere in Canton auth

Concrete contract:

- grant type is always `client_credentials`
- the token request includes `client_assertion_type = urn:ietf:params:oauth:client-assertion-type:jwt-bearer`
- the token request includes `client_assertion = <signed JWT>`
- the JWT header uses `alg = RS256`
- `key-id` is optional
- the JWT header includes `kid` when `key-id` is present
- the JWT claims are:
  - `iss = client-id`
  - `sub = client-id`
  - `aud = <canonical token-endpoint URI derived from address, port, and path>`
  - `iat = now`
  - `exp = now + 30s`
  - `jti = <fresh random identifier per assertion>`
- the participant never reuses a previously signed client assertion across token requests
- `scope` is optional and is sent as an OAuth token-request field when present
- `audience` is optional and is sent as an OAuth token-request field when present
- the signed assertion is never persisted
- the signed assertion, raw private key material, and key file contents are never logged

Key and assertion handling:

- RSA DER/PKCS8 is the only supported signing-key format
- private keys are re-read when a new client assertion is produced so that key rotation takes effect on the next token acquisition without requiring token-manager restart
- assertion lifetime is fixed and short in the first implementation rather than becoming a new operator-facing tuning knob

Accepted security tradeoff:

- issued OAuth access tokens remain bearer tokens
- if a bearer token is exfiltrated, it can be replayed until expiry
- this is accepted in exchange for a simpler and more canonical external-call auth contract

## Request Flow

### Business call flow

For one outer external-call attempt:

1. `HttpExtensionServiceClient` calculates the remaining `maxTotalTimeout` budget.
2. It calls `ExternalCallAuthProvider.prepareRequest(deadline)`.
3. `prepareRequest` returns `PreparedAuth`.
4. `HttpExtensionServiceClient` sends the request to `/api/v1/external-call` with the existing business headers unchanged, the auth header from `PreparedAuth`, and the per-attempt timeout clamped to the remaining outer budget.
5. If the response is not `401`, that response is the outcome of the outer attempt.
6. If the response is `401`, `HttpExtensionServiceClient` reuses the locally generated outbound resource request id for that HTTP interaction, extracts `statusCode` and the first `WWW-Authenticate` header from the concrete `HttpResponse`, builds `AuthResponseContext`, and calls `ExternalCallAuthProvider.handleResponse(responseContext, preparedAuth, deadline)`.
7. `handleResponse` may perform one auth-local replay inside the same outer attempt by returning `ReplayOnceWithFreshAuth(nextPreparedAuth)`.
8. The final response produced by that auth-local replay, or the original non-`401` response, becomes the outcome of the outer attempt.
9. The outer retry loop then classifies that outer-attempt outcome as success, retryable failure, or terminal failure.

### Token rejection policy

The invalidation policy is:

- invalidate cached OAuth token on `401 Unauthorized` from the resource server only if the rejected token still matches the auth provider's current token
- replay the same business request once with a freshly acquired token, subject to the existing outer timeout budget
- the `401` replay is an auth-local replay inside one business-request attempt; it does not consume one of the configured outer `maxRetries` slots
- do not invalidate on `403`, `404`, `429`, `5xx`, timeouts, or transport failures
- record `AuthResponseContext.wwwAuthenticate` when present for debugging; invalidation does not depend on that header

This keeps the policy precise while remaining compatible with providers that omit `WWW-Authenticate`.

Exact control flow for `401` is:

1. The initial resource-server call returns `401`.
2. `handleResponse` checks whether `PreparedAuth.tokenUsed` is still the current cached token.
3. If it is, the auth provider invalidates that cached token.
4. `handleResponse` performs foreground token acquisition using the remaining outer deadline and returns `ReplayOnceWithFreshAuth(nextPreparedAuth)`.
5. `HttpExtensionServiceClient` replays the same business request once with `nextPreparedAuth`.
6. No second auth-local replay is allowed inside the same outer attempt.

Outcome handling after the auth-local replay is:

- replay returns `200`
  - the whole external call succeeds
- replay returns `401`
  - the outer attempt ends with terminal `401`
  - the outer retry loop does not retry
- replay returns `400`, `403`, or `404`
  - the outer attempt ends with that terminal response
  - the outer retry loop does not retry
- replay returns `408`, `429`, `500`, `502`, `503`, or `504`
  - that response becomes the outcome of the outer attempt
  - the outer retry loop treats it exactly like any other retryable outer-attempt failure
  - if the outer retry loop retries, that consumes one `maxRetries` slot
- replay fails with a retryable transport exception mapped to `408` or `503`
  - that mapped failure becomes the outcome of the outer attempt
  - the outer retry loop treats it exactly like any other retryable outer-attempt failure
  - if the outer retry loop retries, that consumes one `maxRetries` slot
- replay fails during foreground token acquisition
  - that token-acquisition failure becomes the outcome of the outer attempt
  - the outer retry loop classifies it from the flattened boundary status in the same way as any other outer-attempt failure
  - if the outer retry loop retries, that consumes one `maxRetries` slot

### Retry composition

The current outer retry loop in `HttpExtensionServiceClient` remains the only business-request retry loop.

Composition rule:

- token endpoint retries stay inside the token manager / token client
- business-request retries stay inside `HttpExtensionServiceClient.callWithRetry`
- foreground token acquisition and the resource-server business call consume the same outer deadline
- shared retry helpers are limited to pure utility code such as backoff calculation; retry ownership and control flow remain separate

Outer business-request retry timing uses:

- `retry-initial-delay` as the base delay before the first outer retry
- `retry-max-delay` as the cap for outer retry backoff and `Retry-After` handling

Retry accounting rule:

- `maxRetries` counts only completed outer-attempt retries
- one outer attempt may include:
  - one initial resource-server request, and
  - at most one auth-local replay after `401`
- the auth-local replay does not increment the outer attempt counter and does not consume a `maxRetries` slot
- any retryable result produced after the auth-local replay is treated as the final result of that outer attempt
- if the outer loop retries after that result, the outer attempt counter increments once

This means foreground token acquisition needs a deadline-aware API. The auth layer cannot assume it has a fresh timeout budget independent from the external call.

Final timeout rule:

- `HttpExtensionServiceClient` computes one absolute deadline from `max-total-timeout`
- every foreground HTTP connect attempt uses an effective connect timeout of `min(connect-timeout, remaining outer budget)` and can occur only while that effective timeout is positive
- every foreground token-endpoint call clamps its timeout to the remaining budget
- every resource-server call clamps its timeout to the remaining budget
- neither side may issue a request once the remaining budget is non-positive
- the implementation must enforce the outer deadline during connect time as well as request time; keeping a longer client-level connect timeout without additional deadline enforcement is not sufficient
- background refresh is excluded from that outer deadline because it is not part of a business request, but it still uses the configured per-attempt timeout and token-manager retry limits

## Determinism

OAuth metadata must not enter the Daml-level business semantics.

What stays unchanged:

- business request path
- `X-Daml-External-Function-Id`
- `X-Daml-External-Config-Hash`
- `X-Daml-External-Mode`
- request/response body contract
- submission vs validation business meaning

What changes:

- outbound auth headers
- token acquisition traffic

Those changes must remain operational only. They must not change the external function result for equivalent submission and validation calls.

Enforceable invariant:

- for a fixed `(extensionId, functionId, configHash, input, mode)`, successful business responses must not depend on access-token claims, client-assertion timestamps, `jti`, or OAuth client identity
- OAuth gates whether the participant is allowed to reach the service, but once authorized it does not act as an additional business input that makes submission and validation diverge

## Error Model

The current `ExtensionCallError` surface is too flat for OAuth. Internally, the participant should distinguish:

- token acquisition failure
- token rejection by the resource server
- resource-server transport failure
- resource-server application error

Implementation rule:

- keep the internal error ADT structured
- flatten to the current `ExternalCallError` shape only at the boundary to the Daml engine
- use the structured error class for logs, metrics, and retry decisions

This keeps the external-call protocol stable while satisfying the requirement for clear failure classification.

### Engine-facing boundary mapping

`ExtensionServiceExternalCallHandler` continues to expose only:

- `statusCode`
- `message`
- `requestId`

The flattening rule is:

- token acquisition failure
  - if the token endpoint returned an HTTP response, preserve that HTTP status code
  - if token acquisition timed out before an HTTP response, including while waiting on a shared in-flight acquisition future, return `408`
  - if token acquisition failed due to connect or I/O failure before an HTTP response, return `503`
  - if token acquisition failed due to local signing-key reload, local auth-material failure, or other participant-side auth setup failure at call time, return `500`
  - if token acquisition failed because the token response was malformed, omitted required fields, or returned an unsupported `token_type`, return `502`
  - prefix the message with `OAuth token acquisition failed:`
- token rejection by the resource server
  - after the auth-local replay is exhausted, return `401`
  - use the message `Unauthorized - OAuth token rejected by resource server`
- resource-server transport failure
  - preserve the current transport-derived status mapping used by `HttpExtensionServiceClient`
  - preserve the current transport-style messages
- resource-server application error
  - preserve the resource server's HTTP status code
  - preserve the current body-to-message mapping used by `HttpExtensionServiceClient`

Request-id rule:

- the boundary `requestId` is the participant-generated outbound correlation id from the last HTTP interaction that determined the final failure returned for that outer attempt
- if the final failure is a token-endpoint HTTP failure before any replay request is sent, return `PreparedAuth.tokenEndpointRequestId` when present
- if the final failure is the initial resource-server response and no auth-local replay is performed, return the initial resource-server request id
- if a `401` triggers token acquisition and then a replay request is sent, the replay request id supersedes both the original `401` request id and `PreparedAuth.tokenEndpointRequestId`
- if token acquisition after a `401` fails before any replay request is sent, return the token-endpoint request id when a token-endpoint HTTP request was sent
- if token acquisition after a `401` fails before any token-endpoint HTTP request was sent, return the original `401` resource-server request id
- if the failure occurred before any HTTP request was sent, return `None`

## Validation

### Current behavior

`HttpExtensionServiceClient.validateConfiguration()` currently performs a best-effort POST to `/api/v1/external-call` using `_health` as the function id and treats any HTTP response as evidence that the service is reachable.

The current `ExtensionValidationResult.Valid | Invalid(errors)` shape is too weak for the final design because it cannot distinguish local failures from remote failures or fatal findings from tolerated findings.

### Proposed behavior

The current startup-validation wiring is incomplete:

- `EngineExtensionsConfig` already carries extension-validation booleans
- `ExtensionServiceManager.validateAllExtensions()` exists
- `ParticipantNode` does not currently call it during startup

The final design makes startup validation explicit rather than describing it as already wired.

For OAuth-enabled extensions, validation remains globally controlled through `EngineExtensionsConfig`, and this change replaces the current validation booleans with one final mode field while retaining the existing test-only `echoMode` toggle:

- `validation-mode = off | local | best-effort-remote | strict-remote`

Wiring rule:

- `ParticipantNode` creates `ExtensionServiceManager`
- before the participant exposes services, `ParticipantNode` invokes `validateAllExtensions()`
- `ExtensionServiceManager` executes the checks implied by `validation-mode`
- startup failure is derived solely from `validation-mode`, not from a second fail/ignore boolean

Echo-mode rule:

- `EngineExtensionsConfig.echoMode` remains in the final design as a test-only bypass
- when `echoMode = true`, `ExtensionServiceManager` instantiates `EchoExtensionServiceClient` for every configured extension and does not construct HTTP clients, auth providers, token managers, or remote-validation probes for those extensions
- in echo mode, `validateAllExtensions()` still returns one `ExtensionValidationReport` per configured extension, but each report is empty-success: `localErrors = Seq.empty`, `remoteErrors = Seq.empty`, `remoteWarnings = Seq.empty`
- therefore startup succeeds in every `validation-mode` when `echoMode = true`
- the OAuth behavior specified in this document applies only when `echoMode = false`

Validation result shape:

- replace `ExtensionValidationResult.Valid | Invalid(errors)` with a structured per-extension report type
- define that type in `community/participant/src/main/scala/com/digitalasset/canton/participant/extension/ExtensionService.scala`

Concrete shape:

- `final case class ExtensionValidationReport(`
- `  extensionId: String,`
- `  localErrors: Seq[String],`
- `  remoteErrors: Seq[String],`
- `  remoteWarnings: Seq[String],`
- `)`

Derived semantics:

- `localErrors`
  - fatal in every mode except `off`
- `remoteErrors`
  - fatal only in `strict-remote`
  - reported as warnings in `best-effort-remote`
- `remoteWarnings`
  - non-fatal diagnostics that never block startup

API rule:

- `ExtensionServiceClient.validateConfiguration(validationMode)` returns `ExtensionValidationReport`
- `ExtensionServiceManager.validateAllExtensions()` returns `Map[String, ExtensionValidationReport]`
- startup success/failure is computed by `ExtensionServiceManager` from those reports and the global `validation-mode`
- the validation report type does not itself encode startup success/failure

Fatal local-validation rule:

- in every mode except `off`, local validation failures are fatal to startup
- local validation failures include malformed config, mutually inconsistent config, unreadable private keys, unreadable certificate or trust files, and invalid TLS material
- best-effort remote mode is lenient only about remote failures; it is not lenient about local misconfiguration

Global validation modes:

- `off`
  - skip startup validation entirely
- `local`
  - validate config completeness
  - validate mutual exclusivity
  - load private keys and TLS trust material
  - build TLS contexts
  - do not hit remote endpoints
  - fail startup on any local validation error
- `best-effort-remote`
  - do local validation
  - perform token acquisition
  - attempt a transport-only resource-server reachability probe
  - fail startup on any local validation error
  - report remote validation failures but do not fail startup on them
- `strict-remote`
  - same checks as `best-effort-remote`
  - fail startup on any local validation error
  - fail startup if remote auth validation fails

Mixed-extension semantics:

- one global `validation-mode` applies to every configured extension
- extensions with `auth.mode = none` run transport/config validation only
- extensions with `auth.mode = oauth` run transport/config validation plus OAuth-specific validation
- in remote modes, `auth.mode = none` performs only the resource-server transport probe
- in remote modes, `auth.mode = oauth` performs token acquisition plus the resource-server transport probe

Startup validation algorithm:

1. Sort configured extensions by extension id.
2. Run local validation for every extension and collect all local results.
3. For any extension that failed local validation, record the local errors and skip remote validation for that extension.
4. For locally valid extensions, run the remote checks required by the global `validation-mode`.
5. Aggregate results for all extensions into one deterministic startup-validation report keyed by extension id.

Startup outcome rule:

- `off`
  - perform no validation
  - produce no startup-validation report
- `local`
  - startup succeeds only if every extension passes local validation
  - startup failure reports all local validation failures across the extension set
- `best-effort-remote`
  - startup succeeds only if every extension passes local validation
  - remote validation failures are aggregated and logged after validation completes
  - startup continues even if one or more extensions fail remote validation
- `strict-remote`
  - startup succeeds only if every extension passes local validation and every locally valid extension passes remote validation
  - startup failure reports all local and remote validation failures across the extension set

Remote validation must not send a synthetic business request through `/api/v1/external-call`.

Final remote-probe rule:

- token-endpoint validation performs a real token acquisition
- resource-server validation uses a dedicated raw transport-validation helper, not `HttpExtensionServiceClient`
- the helper performs only DNS resolution, TCP connect, and, when TLS is enabled, SSL/TLS handshake using the same trust material as the runtime resource-server client
- the helper does not send an HTTP method, path, body, or headers
- validation must not send `X-Daml-External-*` headers and must not invoke a Daml business function such as `_health`

This keeps the validation control point global while satisfying the requirement to avoid business-function invocation.

## Observability

### Logging

Add structured logs for:

- token acquisition start/success/failure
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

### Metrics

Add participant metrics under a new external-call subtree rather than inventing a separate metrics root.

Initial metrics:

- token acquisition success count
- token acquisition failure count
- token refresh count
- token invalidation count
- token cache hit/miss count
- auth latency timer

## Rollout Assumption

This design assumes there are no existing external-call users to preserve.

Therefore:

- the final config model can replace the current `host` / `port` / `useTls` / `tlsInsecure` / `jwt` / `jwtFile` shape directly
- the implementation does not need a compatibility alias layer for static bearer tokens
- the documented production path is simply OAuth with `private_key_jwt` over standard TLS

## Repo Migration Checklist

The lack of a product compatibility layer does not remove the need for an internal repo migration. The implementation must update all repo-internal call sites that still assume the legacy config shape.

Required migration steps:

- replace `ExtensionServiceConfig` and related config ADTs with the final endpoint/auth model
- update `community/app-base/src/main/scala/com/digitalasset/canton/config/CantonConfig.scala` readers and writers for the new config structure
- update participant test fixtures and helpers that construct `ExtensionServiceConfig` directly
- update external-call integration tests to build the new endpoint/auth config shape
- update config snippets, sample configs, and documentation-backed test resources that use legacy extension config fields
- replace `EngineExtensionsConfig.validateExtensionsOnStartup` / `failOnExtensionValidationError` with the final `validationMode` field throughout config parsing and tests, while retaining `echoMode`
- replace any tests that assume `_health`-based validation with tests for the structured validation report and transport-only probing
- remove legacy static-token and `tlsInsecure` assumptions from external-call-specific tests

## Code Impact

### Existing files likely to change

- `community/participant/src/main/scala/com/digitalasset/canton/participant/ParticipantNode.scala`
  - pass `Clock` into `ExtensionServiceManager`
  - invoke startup validation before services are exposed
  - register `ExtensionServiceManager` in the node closeable set
- `community/participant/src/main/scala/com/digitalasset/canton/participant/config/ExtensionServiceConfig.scala`
  - replace the legacy transport/auth fields with an explicit endpoint and auth config model
  - replace the current validation booleans with one global validation-mode enum while retaining `echoMode` as a test-only bypass
- `community/participant/src/main/scala/com/digitalasset/canton/participant/extension/ExtensionServiceManager.scala`
  - stop relying on one globally shared `HttpClient` for all auth/TLS cases
  - instantiate resolved auth providers
  - own auth-provider lifecycle and validation execution
- `community/participant/src/main/scala/com/digitalasset/canton/participant/extension/HttpExtensionServiceClient.scala`
  - remove token lifecycle logic
  - integrate auth provider and structured failure classification
  - clamp effective connect timeout and request timeout to the remaining outer deadline
- `community/participant/src/main/scala/com/digitalasset/canton/participant/extension/ExtensionService.scala`
  - replace `ExtensionValidationResult` with `ExtensionValidationReport`
  - update `ExtensionServiceClient.validateConfiguration(...)` to return the structured validation report

### New files likely to be added

- `community/participant/src/main/scala/com/digitalasset/canton/participant/extension/auth/*`
- `community/participant/src/main/scala/com/digitalasset/canton/participant/extension/HttpTransportValidationHelper.scala`

## Test Plan

### Unit tests

Add unit coverage for:

- auth config parsing and exclusivity
- OAuth token acquisition success/failure
- concurrent callers sharing one token acquisition
- pre-expiry refresh
- token-conditional invalidation on `401`
- audience and scope propagation
- private-key and certificate loading failures
- deadline composition between auth and business-request retries
- fixed-lifetime `private_key_jwt` assertion construction
- background refresh using an injected clock rather than wall-clock sleeps

The most natural homes are:

- `community/participant/src/test/scala/com/digitalasset/canton/participant/extension/*`
- new auth-specific test files under `community/participant/src/test/scala/com/digitalasset/canton/participant/extension/auth/*`

### Integration tests

Extend the current external-call integration suite under:

- `community/app/src/test/scala/com/digitalasset/canton/integration/tests/externalcall/*`

Integration coverage:

- unauthenticated external calls still work under `auth.mode = none`
- OAuth-protected call succeeds end to end
- expired token refreshes successfully
- `401` invalidates token and the same business request is replayed once with a fresh token
- submission and validation both succeed under the same OAuth config
- submission and validation produce the same business response even though access tokens and client assertions differ between runs
- signing key rotation takes effect on the next token acquisition
- resource-server or token-endpoint certificate rotation takes effect after participant restart
- remote validation does not send a business `_health` call

Integration tests pair `MockExternalCallServer` with a dedicated `MockOAuthServer`.

Testing infrastructure requirement:

- auth lifecycle tests should use an injectable clock
- token issuance is mocked through a dedicated token client or `MockOAuthServer`, not through the resource-server mock

## Settled Design Decisions

The following design choices are settled for this draft:

1. Outbound OAuth reuses ledger identity-provider semantics only; it does not reference named identity-provider definitions directly.
2. A `401` invalidates cached auth state and causes the same business request to be replayed once with a fresh token, subject to the existing outer timeout budget.
3. The final config model replaces the legacy resource-server transport fields with a `TlsClientConfig`-style endpoint block.
4. `private_key_jwt` client authentication supports RSA keys in DER/PKCS8 format.
5. The token response must provide usable expiry metadata. Providers that do not provide it are rejected.
6. Auth validation mode is configured globally through one `EngineExtensionsConfig.validationMode` setting introduced by this change.
7. `EngineExtensionsConfig.echoMode` remains as a test-only bypass; when enabled, external-call clients are echo clients and OAuth/HTTP validation is skipped by returning empty-success validation reports.
