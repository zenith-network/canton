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
5. TLS config moves toward existing `TlsClientConfig` semantics for both the resource server and the token endpoint.
6. The final state uses one canonical production auth contract: OAuth with `private_key_jwt` client authentication over standard TLS.

## Proposed Runtime Architecture

### New participant-side auth boundary

Add a new package under `community/participant/src/main/scala/com/digitalasset/canton/participant/extension/auth/` with the following responsibilities:

- resolve auth config into a concrete auth strategy
- decorate outbound business requests with auth material
- invalidate cached auth state on explicit auth rejection
- validate local auth configuration and, optionally, remote auth reachability

Proposed logical types:

- `ExternalCallAuthConfig`
- `ExternalCallAuthProvider`
- `NoAuthProvider`
- `OAuthExternalCallAuthProvider`
- `OAuthAccessTokenManager`
- `OAuthTokenClient`
- `OAuthClientAuthenticationConfig`

The important design point is the boundary, not the exact class names.

### `HttpExtensionServiceClient` after refactor

`HttpExtensionServiceClient` should keep:

- endpoint shape `/api/v1/external-call`
- `X-Daml-External-*` headers
- request ID generation
- resource-server HTTP transport
- outer transport retry budget

It should stop owning:

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

Recommended shape:

- `OAuthAccessTokenWithExpiry(accessToken: String, expiresAt: CantonTimestamp, tokenType: String)`
- `OAuthAccessTokenManagerConfig`

`OAuthAccessTokenManagerConfig` should reuse `AuthenticationTokenManagerConfig` directly where that is practical. If a wrapper is introduced for naming clarity, it should delegate to the same fields and defaults rather than inventing new lifecycle vocabulary.

### Token acquisition client

`OAuthTokenClient` should be a small HTTP client that:

- talks only to the configured token endpoint
- uses its own TLS settings
- receives an absolute deadline from the outer external-call attempt
- returns `OAuthAccessTokenWithExpiry`
- never logs secret-bearing inputs or outputs

Initial grant type:

- `client_credentials`

Initial token response expectations:

- `access_token`
- `token_type`
- `expires_in` or another reliable way to determine expiry

If the provider response does not carry usable expiry information, the implementation must not silently turn the token cache into an unbounded cache.

## Proposed Config Model

### High-level direction

Keep auth nested under `ExtensionServiceConfig`, but split the config into explicit blocks:

- resource server transport
- auth mode
- request/retry settings
- declared functions

The exact field names can still move, but the shape should become explicit.

### Auth modes

Proposed auth modes:

- `none`
- `oauth`

Clarification:

- The canonical production contract is still `auth.mode = oauth`.
- Sender-constrained mechanisms such as mTLS are out of scope for this design.

### Illustrative config shape

This is illustrative, not final HOCON:

```hocon
extensions = {
  test-ext = {
    name = "test-ext"

    endpoint = {
      address = "ext.example.internal"
      port = 443
      tls = {
        enabled = true
        trust-collection-file = "/etc/canton/ext-ca.pem"
      }
    }

    auth = {
      mode = oauth

      oauth = {
        issuer = "https://issuer.example.internal"
        token-endpoint = "https://issuer.example.internal/oauth2/token"
        target-audience = "ext.example.internal"
        target-scope = "external.call.invoke"
        token-manager = {
          refresh-auth-token-before-expiry = 20s
          retries = 20
          min-retry-interval = 500ms
        }
        client-authentication = {
          type = private-key-jwt
          client-id = "participant1"
          key-id = "participant1-key"
          private-key-file = "/etc/canton/oauth-client-key.der"
        }
        token-endpoint-tls = {
          enabled = true
          trust-collection-file = "/etc/canton/issuer-ca.pem"
        }
      }
    }

    request-timeout = 8s
    max-total-timeout = 25s
    max-retries = 3
    request-id-header = "X-Request-Id"
  }
}
```

### Why this shape fits the current codebase

- It keeps auth under the per-extension config, which matches how `ExtensionServiceManager` already instantiates one client per extension.
- It keeps transport retry fields where `HttpExtensionServiceClient` already consumes them.
- It reuses `targetAudience` and `targetScope` terminology from `AuthServiceConfig`.
- It creates room for token-endpoint TLS without overloading the resource-server `useTls` and `tlsInsecure` booleans.

### Resource-server TLS

The current `useTls` and `tlsInsecure` booleans are too weak for OAuth-enabled deployments because:

- they cannot express different trust roots for the resource server and token endpoint
- `ExtensionServiceManager` currently applies insecure TLS globally if any extension enables it

The design should replace them with existing `TlsClientConfig`-style semantics for both destinations rather than carry forward the legacy external-call fields.

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
- issuer/audience/lifetime semantics already exist elsewhere in Canton auth

Draft behavior:

- build a short-lived client assertion JWT
- sign it locally
- post it to the token endpoint as OAuth client authentication
- never persist the assertion
- never log the assertion or private key path contents

Accepted security tradeoff:

- issued OAuth access tokens remain bearer tokens
- if a bearer token is exfiltrated, it can be replayed until expiry
- this is accepted in exchange for a simpler and more canonical external-call auth contract

## Request Flow

### Business call flow

For one external-call attempt:

1. `HttpExtensionServiceClient` calculates the remaining `maxTotalTimeout` budget.
2. It asks the auth provider to decorate the resource-server request.
3. The auth provider may:
   - return immediately for `none`
   - synchronously or asynchronously acquire a cached OAuth token
4. `HttpExtensionServiceClient` sends the request to `/api/v1/external-call` with the existing business headers unchanged.
5. Response classification happens in two layers:
   - auth layer decides whether the response means "invalidate auth state"
   - transport layer decides whether the request may be retried

### Token rejection policy

The invalidation policy should be explicit:

- invalidate cached OAuth token on `401 Unauthorized` from the resource server
- replay the same business request once with a freshly acquired token, subject to the existing outer timeout budget
- do not invalidate on `403`, `404`, `429`, `5xx`, timeouts, or transport failures
- record the `WWW-Authenticate` header when present for debugging, but do not require it for invalidation

This keeps the policy precise while remaining compatible with providers that omit `WWW-Authenticate`.

### Retry composition

The current outer retry loop in `HttpExtensionServiceClient` remains the only business-request retry loop.

Composition rule:

- token endpoint retries stay inside the token manager / token client
- business-request retries stay inside `HttpExtensionServiceClient.callWithRetry`
- both consume the same outer deadline

This means token acquisition needs a deadline-aware API. The auth layer cannot assume it has a fresh timeout budget independent from the external call.

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

## Error Model

The current `ExtensionCallError` surface is too flat for OAuth. Internally, the participant should distinguish:

- token acquisition failure
- token rejection by the resource server
- resource-server transport failure
- resource-server application error

Recommended direction:

- keep the internal error ADT structured
- flatten to the current `ExternalCallError` shape only at the boundary to the Daml engine
- use the structured error class for logs, metrics, and retry decisions

This keeps the external-call protocol stable while satisfying the requirement for clear failure classification.

## Validation

### Current behavior

`HttpExtensionServiceClient.validateConfiguration()` currently performs a best-effort POST to `/api/v1/external-call` using `_health` as the function id and treats any HTTP response as evidence that the service is reachable.

### Proposed behavior

For OAuth-enabled extensions, validation should remain globally controlled, consistent with the current extension validation model in `EngineExtensionsConfig`.

Recommended global validation modes:

- `local`
  - validate config completeness
  - validate mutual exclusivity
  - load private keys and TLS trust material
  - build TLS contexts
  - do not hit remote endpoints
- `best-effort-remote`
  - do local validation
  - attempt token acquisition
  - attempt the current `_health` resource-server reachability call
  - report failures but do not fail startup unless existing global startup settings already say to fail
- `strict-remote`
  - same checks as `best-effort-remote`
  - startup fails if remote auth validation fails

This keeps the validation control point aligned with the current global extension validation structure instead of introducing per-extension validation policy.

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
- issuer
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

Recommended first metrics:

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

## Code Impact

### Existing files likely to change

- `community/participant/src/main/scala/com/digitalasset/canton/participant/config/ExtensionServiceConfig.scala`
  - replace the legacy transport/auth fields with an explicit endpoint and auth config model
- `community/participant/src/main/scala/com/digitalasset/canton/participant/extension/ExtensionServiceManager.scala`
  - stop relying on one globally shared `HttpClient` for all auth/TLS cases
  - instantiate resolved auth providers
- `community/participant/src/main/scala/com/digitalasset/canton/participant/extension/HttpExtensionServiceClient.scala`
  - remove token lifecycle logic
  - integrate auth provider and structured failure classification
- `community/participant/src/main/scala/com/digitalasset/canton/participant/extension/ExtensionService.scala`
  - expand internal error taxonomy if needed

### New files likely to be added

- `community/participant/src/main/scala/com/digitalasset/canton/participant/extension/auth/*`
- possibly a small HTTP TLS helper if existing gRPC-only TLS helpers cannot be reused directly

## Test Plan

### Unit tests

Add unit coverage for:

- auth config parsing and exclusivity
- OAuth token acquisition success/failure
- concurrent callers sharing one token acquisition
- pre-expiry refresh
- invalidation on `401`
- audience and scope propagation
- private-key and certificate loading failures
- deadline composition between auth and business-request retries

The most natural homes are:

- `community/participant/src/test/scala/com/digitalasset/canton/participant/extension/*`
- new auth-specific test files under `community/participant/src/test/scala/com/digitalasset/canton/participant/extension/auth/*`

### Integration tests

Extend the current external-call integration suite under:

- `community/app/src/test/scala/com/digitalasset/canton/integration/tests/externalcall/*`

Recommended additions:

- unauthenticated external calls still work under `auth.mode = none`
- OAuth-protected call succeeds end to end
- expired token refreshes successfully
- `401` invalidates token and the same business request is replayed once with a fresh token
- submission and validation both succeed under the same OAuth config
- signing key rotation
- resource-server or token-endpoint certificate rotation

The current `MockExternalCallServer` should either:

- grow a token endpoint context, or
- be paired with a dedicated `MockOAuthServer`

The second option is cleaner because it keeps the resource-server protocol mock separate from OAuth token issuance.

## Settled Design Decisions

The following design choices are settled for this draft:

1. Outbound OAuth reuses ledger identity-provider semantics only; it does not reference named identity-provider definitions directly.
2. A `401` invalidates cached auth state and causes the same business request to be replayed once with a fresh token, subject to the existing outer timeout budget.
3. The final config model replaces the legacy resource-server transport fields with a `TlsClientConfig`-style endpoint block.
4. `private_key_jwt` client authentication supports RSA keys in DER/PKCS8 format.
5. The token response must provide usable expiry metadata. Providers that do not provide it are rejected.
6. Auth validation mode is configured globally, aligned with the existing `EngineExtensionsConfig` startup-validation controls.
