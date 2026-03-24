# External Call OAuth Tech Spec

## Purpose and Baseline

This document defines OAuth-based service-to-service authentication for participant external calls.

The goal is to add OAuth without changing the Daml external-call business protocol.

This spec assumes:

- production uses `auth.mode = oauth` with `client_credentials` and `private_key_jwt` over TLS
- OAuth changes transport and authentication behavior only; it must not change Daml-level business semantics
- OAuth configuration remains per extension
- sender-constrained mechanisms such as mTLS are out of scope
- there are no existing external-call users to preserve, so the current static-token configuration can be replaced rather than supported indefinitely

Determinism requirement:

- for a fixed `(extensionId, functionId, configHash, input)`, successful business responses must be identical in `submission` and `validation`
- `mode` remains forwarded unchanged on the existing wire contract, but OAuth must not cause a different successful business response
- access-token claims, client-assertion timestamps, `jti`, and OAuth client identity are transport concerns only and must not act as business input

Current integration point:

1. `ParticipantNode` creates one `ExtensionServiceManager` when `parameters.engine.extensions` is non-empty.
2. `ExtensionServiceManager` creates one `HttpExtensionServiceClient` per configured extension.
3. `ExtensionServiceExternalCallHandler` forwards Daml engine calls to the manager.
4. `HttpExtensionServiceClient` builds the HTTP request, injects the auth header, performs the transport call, classifies the response, and owns request retry logic.

Current implementation constraints:

- `ExtensionServiceConfig` is currently a single per-extension config object containing endpoint, auth, and retry settings
- `HttpExtensionServiceClient` currently reads a literal token from `jwt` or `jwtFile`, injects `Authorization: Bearer <token>` on every request, and treats `401` as terminal
- `HttpExtensionServiceClient.callWithRetry` is already the correct outer retry boundary; retryable outcomes are `408`, `429`, `500`, `502`, `503`, and `504`, while terminal outcomes are `400`, `401`, `403`, and `404`
- `ExtensionServiceExternalCallHandler` only exposes `statusCode`, `message`, and `requestId` to the Daml engine
- existing Canton helpers for JWT signing and TLS semantics should be reused where they help, but OAuth should not import sequencer-auth abstractions wholesale

## Runtime Design

### Ownership

Keep `HttpExtensionServiceClient` as the sole owner of request orchestration, deadlines, retry
integration, and the single auth-local `401` replay.

OAuth support stays inside this existing seam. A small private helper for cached token state may
be used, but no general auth-provider interface or separate auth subsystem is introduced for
`external_call`.

`ExtensionServiceManager` continues to create one client per configured extension, and
`ExtensionServiceExternalCallHandler` remains a thin boundary mapper.

No background refresh task is introduced.

Runtime HTTP client reuse is an implementation detail, but different TLS settings must not be
collapsed into one shared trust configuration.

### Request Execution, Retries, and Deadlines

`HttpExtensionServiceClient` keeps the existing resource-server protocol: endpoint shape
`/api/v1/external-call`, `X-Daml-External-*` headers, participant-generated request ids, response
classification, and the outer retry budget.

For one external-call operation, the client computes one absolute deadline from
`max-total-timeout` before the first outer attempt. Every outer attempt uses the remaining budget
against that fixed deadline.

One outer attempt is:

1. Compute the remaining budget.
2. If `auth.mode = none`, skip auth work. If `auth.mode = oauth`, obtain a valid access token against the same remaining budget.
3. Send the request to `/api/v1/external-call` with the existing `X-Daml-External-*` headers unchanged, plus `Authorization: Bearer <token>` when OAuth is enabled. Connect and request timeouts are clamped to the remaining budget.
4. If the response is not `401`, that response is the outcome of the outer attempt.
5. If the response is `401` under `auth.mode = oauth`, discard the cached token only if it is the same token that was attached to the rejected request.
6. Acquire a fresh token against the same outer deadline.
7. Replay the resource request once with the fresh token.
8. Feed the replay result, or the original non-`401` result, back into the existing outer retry loop.

Rules:

- `callWithRetry` remains the only business-request retry loop
- `maxRetries` counts only outer retries
- one outer attempt may include one initial resource request and at most one auth-local replay
- the auth-local replay does not consume a `maxRetries` slot
- `401` is the only resource response that triggers auth-specific recovery after a request has been sent
- `403`, `404`, `429`, `5xx`, timeouts, and transport failures do not invalidate the cached token
- after the auth-local replay, normal status handling resumes: `200` succeeds; `400`, `401`, `403`, and `404` are terminal; `408`, `429`, `500`, `502`, `503`, and `504` remain retryable outer-attempt outcomes
- if no positive deadline budget remains, neither token acquisition nor a resource request is started

### Token Acquisition and Caching

OAuth uses simple on-demand token caching:

- a cached access token may be reused until expiry
- there is no proactive refresh and no background work
- an expired or invalidated cached token is replaced on the next business request that needs OAuth
- synchronization of concurrent cache misses is an implementation detail, not part of the public contract

Token request and response rules:

- token acquisition uses `grant_type = client_credentials`
- optional `scope` and `audience` are sent as token-request fields when configured
- client authentication uses `private_key_jwt`
- required token response fields are `access_token`, `token_type`, and `expires_in`
- `token_type` must be `Bearer`, matched case-insensitively
- access tokens are treated as opaque bearer tokens; the participant does not parse or locally verify them

Client assertion rules:

- use `RS256`
- emit `kid` when configured
- claims are `iss = client-id`, `sub = client-id`, `aud = <token-endpoint URI>`, `iat = now`, `exp = now + 30s`, and `jti = <fresh random identifier>`
- assertions are one-use only and are never logged or persisted
- RSA DER/PKCS8 is the supported signing-key format

Key and trust material:

- signing-key and trust material are loaded during local validation and client construction rather than re-reading files on every token request
- rotation therefore takes effect on participant restart
- hot reload of OAuth key material is out of scope

Token-endpoint failures use the same retry budget as resource requests. There is no second retry
policy for OAuth. HTTP `408`, `429`, `500`, `502`, `503`, and `504`, plus transient connect,
request-timeout, and I/O failures, are retryable through the outer loop. Malformed token responses
are terminal for the current outer attempt.

## Configuration

OAuth should extend the existing per-extension config rather than trigger a broad transport-config
refactor.

Configuration rules:

- keep `ExtensionServiceConfig` as the main per-extension config
- replace `jwt` / `jwtFile` with an explicit `auth` block
- keep existing request lifecycle settings at the extension level: `connect-timeout`, `request-timeout`, `max-total-timeout`, `max-retries`, `retry-initial-delay`, `retry-max-delay`, `request-id-header`, and `declared-functions`
- use the same per-attempt `connect-timeout` and `request-timeout` settings for both resource-server and token-endpoint HTTP calls, clamped by the remaining `max-total-timeout`
- represent resource-server and token-endpoint addresses with Canton's existing `address`, `port`, and `tls` semantics; whether this is done by embedding existing client config types or a thin local wrapper is an implementation detail
- `auth.mode` supports only `none` and `oauth`
- when `auth.mode = oauth`, TLS is required for both the resource server and the token endpoint
- trust-all / insecure TLS remains test-only scaffolding and is not part of the supported OAuth contract

Token-endpoint URI:

- the token endpoint is configured as `https://<address>[:<port>]<path>`
- the same URI is used as the HTTP target and as the `aud` claim in the client assertion
- `path` must start with `/` and must not contain a query string or fragment
- a separate client-assertion audience override is out of scope

Global extension settings:

- keep the existing `EngineExtensionsConfig` knobs: `validateExtensionsOnStartup`, `failOnExtensionValidationError`, and `echoMode`
- do not introduce a separate `validation-mode` enum

### Example Config

```hocon
extension-settings = {
  validate-extensions-on-startup = true
  fail-on-extension-validation-error = true
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
        client-id = "participant1"
        private-key-file = "/etc/canton/oauth-client-key.der"
        key-id = "participant1-key"
        audience = "ext.example.internal"
        scope = "external.call.invoke"
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

## Startup Validation

Keep startup validation local and simple.

The validation result surface remains:

```scala
sealed trait ExtensionValidationResult
object ExtensionValidationResult {
  case object Valid extends ExtensionValidationResult
  final case class Invalid(errors: Seq[String]) extends ExtensionValidationResult
}
```

Contract:

- `ExtensionServiceClient.validateConfiguration()` returns `ExtensionValidationResult`
- `ExtensionServiceManager.validateAllExtensions()` returns `Map[String, ExtensionValidationResult]`
- when `validateExtensionsOnStartup = false`, validation is skipped and `validateAllExtensions()` returns `Map.empty`
- when `validateExtensionsOnStartup = true`, validation runs independently per configured extension
- validation covers malformed or inconsistent config, missing required OAuth fields, `auth.mode = oauth` without TLS, unreadable private-key files, unreadable or invalid trust material, and obviously invalid token-endpoint path or URI construction
- validation does not perform token acquisition or remote HTTP calls
- if `failOnExtensionValidationError = true`, any `Invalid` result fails participant startup; otherwise failures are logged and startup continues
- in `echoMode`, no HTTP or OAuth objects are constructed and all configured extensions validate as `Valid`

`ParticipantNode` may keep using `validateAllExtensions()` as the startup integration point, but
runtime success must not depend on a startup-time token or network probe.

## Error Handling

Keep the existing boundary shape.

- internal OAuth failures are mapped directly to `ExtensionCallError`
- `ExtensionServiceExternalCallHandler` continues to expose only `statusCode`, `message`, and `requestId`

Mapping rules:

- retryable token-endpoint HTTP failures preserve their HTTP status code so they can flow through the existing retry logic
- malformed token responses map to `502`
- local signing, key-loading, and local auth-material failures map to `500`
- after the auth-local replay is exhausted, a resource-server token rejection maps to `401` with message `Unauthorized - OAuth token rejected by resource server`
- resource-server transport and application failures keep the existing mapping already used by `HttpExtensionServiceClient`

Request ID rule:

- return the request id of the HTTP interaction that produced the returned error
- if token acquisition fails before any HTTP request is sent, return `None`

## Observability and Testing

Logging:

- add structured logs for token acquisition start, success, and failure; cache reuse versus reacquisition; token invalidation on `401`; and final external-call failure classification
- never log access tokens, client assertions, private key material, or token-endpoint request bodies

Metrics:

- OAuth-specific metrics are optional; the feature does not depend on adding new metrics

Tests:

- unit coverage for auth config parsing, token acquisition success and failure, cached token reuse, expiry-driven reacquisition, `401` invalidate-and-replay, private-key and trust-material loading failures, and client assertion construction
- integration coverage for `auth.mode = none`, end-to-end OAuth success, expired cached tokens reacquiring on the next call, single `401` replay, submission and validation producing the same successful business response under OAuth, and local startup validation failures
- dedicated token-endpoint test infrastructure is acceptable, but the spec does not require a separate mock server abstraction if existing test helpers can cover the scenario without distorting runtime behavior
