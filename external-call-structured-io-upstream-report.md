# Structured External Call I/O: Upstream End-State Proposal

Status: draft for internal review
Date: 2026-05-06
Audience: Zenith and Digital Asset engineers reviewing the intended final shape of structured `external_call` support

## Executive Summary

We want `external_call` to support normal Daml data structures as input and output.

Today, the external-call API is byte-oriented at the Daml source level. Daml code passes the request payload as a hex-encoded `Text` value and receives the response as another hex-encoded `Text` value. That works for low-level integrations, but it pushes serialization work onto every Daml application. If a user wants to send a record, a variant, a list, or a nested data structure, they must invent and maintain their own encoding scheme.

The proposed end state is that Daml code can call an external service with a normal typed Daml value and receive a normal typed Daml value back. Canton and the Daml runtime would handle the conversion between those typed values and stable LF value bytes.

The Daml-facing API should look like this:

```daml
type BytesHex = Text

externalCall
  : forall input output.
    (Serializable input, Serializable output) =>
    Text       -- extension id
    -> Text    -- function id
    -> BytesHex
    -> input
    -> Update output
```

The important points are:

- `extensionId` and `functionId` remain `Text`.
- `config` remains `BytesHex`, because Daml does not currently have a source-level byte type.
- `input` becomes a normal structured Daml value.
- `output` becomes a normal structured Daml value.
- `input` and `output` must be serializable Daml values.
- `ContractId` values should not be allowed in the first production-ready design.
- Canton should store external-call evidence as bytes in the transaction/protocol layer.
- Runtime errors should use a dedicated external-call error hierarchy, not generic Daml `UserError`.
- The external service should receive a well-defined request envelope containing the value bytes, serialization version, and enough type/schema identity to understand the payload.

This gives Daml users the ergonomic feature they want while keeping the runtime model explicit, deterministic, and reviewable by upstream Daml/Canton maintainers.

This proposal requires coordinated changes in both upstream repositories:

- the Daml repository, which owns the source-level API, standard library wrapper, compiler lowering, LF typechecking, and package validation;
- the Canton repository, which owns interpretation, participant integration, external-service calls, transaction evidence, replay, and validation.

The Canton-side changes are conceptually straightforward: once the runtime receives concrete input and output types, Canton can encode the evaluated input value, call the configured extension service, decode the returned bytes, validate the result against the expected output type, and record the byte-level evidence. The Daml-side changes are likely to be more delicate. They affect the public `DA.External` API, polymorphic builtin lowering, `Serializable` constraints, and the boundary between ordinary stdlib functions and compiler-recognized intrinsics. Those are language/compiler design questions, not just runtime plumbing.

## Short Glossary

This report uses a few Daml/Canton terms:

- **Daml**: the smart contract language used by application developers.
- **Daml-LF / LF**: the lower-level intermediate language produced by the Daml compiler and interpreted by the runtime.
- **Canton**: the synchronization and participant runtime that executes, validates, and records Daml transactions.
- **Participant**: the Canton node that hosts parties and submits or validates transactions on their behalf.
- **Extension service**: a configured participant-side service that Canton can call while interpreting an `external_call`.
- **External-call evidence**: the data recorded in the transaction so later validation can check or replay the external call.
- **Serializable Daml value**: a Daml value that can be represented in the stable LF value format. Ordinary records, variants, lists, optionals, and primitives are in this category. Functions and runtime actions are not.

## Plain-Language Description

`external_call` is a way for Daml code to ask a configured participant-side service for a result during command interpretation.

For example, a Daml contract might need to ask a price oracle for a quote, ask a policy engine for a decision, or ask a deterministic calculation service for a computed result. The external service is not arbitrary application code running on the ledger. It is a configured extension service that the participant knows how to call.

The key challenge is that Daml transactions must remain deterministic and validatable. If a command submission calls an external service, later validation must be able to prove that the same external-call input led to the same recorded external-call output. Canton therefore records the external-call result as part of the transaction evidence.

The current low-level API exposes this byte-level model directly to Daml application authors. Application authors must provide the input as hex text and interpret the output as hex text. That is safe from a low-level protocol perspective, but it is not a good developer experience for structured business data.

The proposed design keeps the protocol evidence byte-oriented, but raises the Daml API to a typed value boundary. In other words:

- Daml authors work with normal Daml values.
- The runtime converts those values to bytes.
- The extension service receives bytes in a documented format.
- The extension service returns bytes in the same documented format.
- The runtime decodes those bytes and checks that they match the expected Daml output type.
- The transaction records the bytes that were actually used.

This keeps the ledger/runtime model precise while removing manual hex encoding from ordinary Daml business logic.

## Motivation

The main motivation is usability, correctness, and avoiding expensive on-ledger text processing.

Most real external-call use cases are not naturally `Text -> Text` or raw bytes-in/bytes-out. They involve structured data:

- a request record with several fields;
- a nested payload;
- optional fields;
- a variant representing the kind of request;
- a list of items;
- a map of attributes;
- a typed response record;
- an error/result variant.

With the current API, each application must decide how to turn those structures into bytes. That leads to repeated boilerplate, inconsistent encodings, and application-level type safety gaps. The Daml compiler cannot help much if everything has already been flattened into hex text.

There is also a Daml-specific performance reason to avoid pushing payload parsing into Daml code. In the current runtime and standard library, common `Text` operations such as length checks, slicing, taking, and dropping are implemented through character-level decomposition and list processing, which can allocate a separate Java string for each character. That makes parser-like code over large hex `Text` payloads disproportionately expensive. Structured external-call input/output avoids this pattern: Daml code works with typed values, while the runtime handles the low-level byte encoding and decoding.

The better user model is:

```daml
quote : PriceQuote <-
  externalCall "price-oracle" "get-quote" config request
```

where `request` is a normal Daml record and `quote` is a normal Daml record.

This is not only nicer to use. It also gives the runtime a clear expected output type. If the service returns malformed bytes, or bytes that decode to the wrong shape, the runtime can reject the result deterministically.

## Goals And Non-Goals

The goal is to make external calls usable with normal Daml business data without weakening Canton validation.

The design should:

- let Daml developers pass structured input values;
- let Daml developers receive structured output values;
- preserve deterministic transaction validation;
- record byte-level external-call evidence in Canton;
- keep the Daml API small and understandable;
- give extension services a documented payload format;
- use explicit error categories;
- be suitable for Digital Asset upstream review.

The design should not try to solve every adjacent problem in the first production version.

The initial design should not:

- add a general Daml `Bytes` type;
- make `config` structured;
- allow `ContractId` values;
- introduce a schema registry;
- make arbitrary internet calls from Daml;
- treat external services as trusted to decide output types;
- hide external-call disclosure from Daml authors.

These exclusions are not permanent. They are scope boundaries that keep the core feature understandable and reviewable.

## Where The Work Lives

This feature should be understood as a two-repository change.

The **Daml repository** owns the part that application developers see and the compiler/LF rules that make the API sound. It would need to define the public `DA.External.externalCall` type, preserve concrete input and output types during compilation, enforce `Serializable` constraints, reject unsupported types, and ensure package validation cannot be bypassed by generated or hand-written LF. This is the more controversial part of the proposal because it asks Daml to provide a principled way for a polymorphic public API to lower into an LF builtin while retaining type information needed by the runtime. The prototype solved this with a direct compiler special case, but an upstreamable version should use a design that Digital Asset's Daml maintainers are comfortable owning long term.

The **Canton repository** owns the runtime and protocol behavior once the Daml/LF layer has made the call explicit. Canton needs to encode the evaluated input value as LF value bytes, invoke the configured extension service, decode the returned bytes, validate the decoded value against the expected output type, categorize failures correctly, and record the external-call evidence needed for replay and validation. These changes are still important, especially for determinism and security, but they fit naturally into the existing external-call runtime model.

The boundary between the two repositories is therefore crucial. Daml must tell Canton exactly which input and output types are expected. Canton must not guess those types from service output. Canton should enforce the runtime byte and validation semantics, while Daml should enforce the language-level typing and serializability rules.

## What We Already Proved

We built a prototype that round-trips a nested Daml record end to end.

The prototype demonstrated this flow:

1. Daml code calls `externalCall` with a structured input value.
2. The Daml/Canton runtime encodes the input value as LF value bytes.
3. A Python external-call server receives the bytes.
4. The Python server echoes the same bytes back.
5. Canton decodes the response bytes as the expected output type.
6. Daml receives a normal typed value.
7. Submission and validation both perform the external call successfully.

The test payload was not just `Text`. It included a nested record with fields such as `Text`, `Int`, `Bool`, lists, and `Optional`.

The prototype therefore proves that the basic mechanism works. It does not prove that the current implementation shape is upstream-ready. In particular, the prototype used a compiler special case and temporarily removed source-level `Serializable` constraints. Those are acceptable prototype shortcuts, but they need to be replaced with a principled design before upstreaming.

## Proposed Final Daml API

The proposed final Daml API is:

```daml
type BytesHex = Text

externalCall
  : forall input output.
    (Serializable input, Serializable output) =>
    Text       -- extension id
    -> Text    -- function id
    -> BytesHex
    -> input
    -> Update output
```

The API has four user-supplied arguments:

1. `extensionId`
2. `functionId`
3. `config`
4. `input`

It returns an `Update output`.

### Extension Id

The `extensionId` identifies the configured extension service.

This should remain `Text`. It is not a payload. It is a routing identifier that Canton uses to select the configured service.

### Function Id

The `functionId` identifies the function within the extension service.

This should also remain `Text`. It lets one configured extension expose multiple deterministic functions.

### Config

The `config` should remain `BytesHex`, where:

```daml
type BytesHex = Text
```

This is necessary because Daml does not currently expose a source-level `Bytes` type. If a Daml author needs to pass opaque bytes as configuration, hex-encoded text is the existing practical representation.

The runtime should validate this value strictly. It should reject invalid hex, odd-length hex, uppercase hex if canonical lowercase is required, and whitespace-padded values. It should not silently trim or lowercase the value before validation.

The reason is subtle but important: Daml source values and transaction evidence must not drift apart. If the Daml value was `"ABCD"` but the runtime silently records bytes as if the value were `"abcd"`, then two different Daml values collapse to the same stored evidence. Upstream review feedback already points toward strict canonical validation at the boundary.

### Input

The `input` argument should be a normal Daml value.

For example:

```daml
data QuoteRequest = QuoteRequest with
    symbol : Text
    quantity : Decimal
    venue : Text
  deriving (Eq, Show)

quote : Quote <-
  externalCall "price-oracle" "quote" config quoteRequest
```

The Daml author should not need to manually serialize `QuoteRequest`.

The runtime should encode the input value using a stable LF value serialization format. The encoded bytes are what get sent to the external service and recorded as external-call input evidence.

### Output

The output should be a normal Daml value of the expected result type.

For example:

```daml
quote : Quote <-
  externalCall "price-oracle" "quote" config quoteRequest
```

The expected output type is `Quote`. The runtime should decode the returned LF value bytes and verify that the decoded value matches `Quote`. If the service returns bytes for the wrong type, the update should fail deterministically.

## What "Arbitrary Daml Data Structures" Should Mean

It is tempting to say that `external_call` should support arbitrary Daml data structures. For an upstream specification, that phrase is too broad.

The precise target should be:

> `external_call` supports serializable Daml values, excluding contract references for the first production design.

This means ordinary business data should work:

- primitive values;
- records;
- variants;
- enums;
- lists;
- optionals;
- maps, assuming canonical serialization is verified;
- nested combinations of the above.

It does not mean every LF type or every runtime value should work. In particular, it should not include functions, `Update` values, type representations, internal runtime values, or contract references.

The word "serializable" matters because the runtime must be able to turn the value into stable bytes and later validate the returned bytes against a type.

## ContractId Policy

The first production-ready design should reject `ContractId` values in external-call input and output.

This is the conservative choice, and it is the right default for upstream.

A `ContractId` is not just a piece of ordinary data. It is a reference to ledger state. It carries questions about:

- visibility;
- authorization;
- activeness;
- explicit contract disclosure;
- participant hosting;
- synchronizer assignment;
- reassignment;
- validation context;
- package upgrades;
- whether the receiving party is allowed to know the contract exists.

Sending a `ContractId` to an external service creates additional security and semantic questions. For example:

- Is the service only allowed to see the opaque contract id?
- Is the service expected to fetch or inspect the contract?
- If the service returns a contract id, who checks that the submitter is allowed to use it?
- Could a service leak contract ids across parties or views?
- Could validation behave differently for different participants because they have different visibility?
- How does this interact with explicit contract disclosure?

These questions are solvable, but they are not part of the core structured-payload feature. They deserve a separate design.

The initial upstream design should therefore state:

> Structured external-call input and output must not contain `ContractId` values.

This should be enforced in two places:

1. Type-level validation should reject external-call input/output types that contain `ContractId`.
2. Value-level validation should reject any contract ids that appear in values despite type-level checks, including local or relative contract ids at lower runtime layers.

This keeps the first design focused on pure business data.

## Runtime Model

The runtime model should be straightforward and deterministic.

At a high level:

1. Daml code calls `externalCall`.
2. The compiler preserves the concrete input and output types.
3. The LF/Speedy runtime evaluates the input value.
4. The runtime validates that the input value is allowed.
5. The runtime encodes the input value as stable LF value bytes.
6. Canton calls the configured extension service.
7. The service returns LF value bytes.
8. The runtime decodes those bytes.
9. The runtime validates the decoded value against the expected output type.
10. The runtime records the external-call evidence in the transaction.
11. Daml code receives the typed output value.

The runtime must not guess the output type from the bytes. The expected output type must come from the Daml call site and must survive compiler lowering into the interpreter.

That last point is critical. Our prototype initially failed because the generic stdlib wrapper hid the concrete output type. Speedy later saw a type variable such as `output` instead of a real type such as `Text` or `EchoPayload`. The production implementation must make that impossible.

## Compiler And Language Requirements

The public Daml function `DA.External.externalCall` should not be implemented as an ordinary polymorphic wrapper that accidentally erases the concrete type information needed by the runtime.

The compiler needs a formal mechanism for this kind of function. There are several possible implementation strategies:

- make `DA.External.externalCall` a compiler-recognized intrinsic wrapper;
- add a general stdlib intrinsic annotation mechanism;
- expose the LF builtin through a cleaner internal primitive API;
- use a type-representation mechanism internally while keeping the public API clean.

The exact implementation can be discussed with Digital Asset maintainers. The requirement is more important than the mechanism:

> The compiled LF must apply `BEExternalCall` to concrete input and output types at the user call site.

The source-level API should also restore `Serializable input` and `Serializable output` constraints. The prototype removed those constraints because generated LF referred to missing `Serializable` dictionaries in downstream package checking. That dictionary-reference issue needs a real fix.

Source-level constraints are not enough by themselves. Hand-written LF or compiler bugs should not be able to instantiate the builtin at unsupported types. LF validation should independently reject non-serializable or disallowed external-call input/output types.

## Service Protocol

The external service should not receive Daml source syntax. It should receive a well-defined request envelope.

The envelope should include:

- request protocol version;
- extension id;
- function id;
- config bytes;
- input LF value bytes;
- input type identity;
- expected output type identity;
- LF value serialization version;
- package identity or package-name/version context as needed;
- execution mode or context if the existing external-call protocol distinguishes submission and validation calls.

The exact wire format is a separate design detail. It could remain HTTP-based. It could be JSON with hex-encoded byte fields. It could later become protobuf or another structured protocol. The important point is that the envelope is explicit.

The service needs enough information to understand the input and produce the right output. In simple deployments, the service may already know the schema from `(extensionId, functionId, config)`. However, including type identity and serialization version makes the protocol more self-describing and easier to debug.

This type metadata does not necessarily need to become consensus-critical transaction data in the first version. It can be derived from the Daml call site during interpretation. The transaction must record the byte-level evidence needed for validation; service-facing metadata can be regenerated from the package and call site.

## Transaction And Validation Model

The transaction/protocol layer should remain byte-oriented.

The transaction evidence for an external call should include at least:

- extension id;
- function id;
- config bytes;
- input bytes;
- output bytes;
- value serialization version.

This is the deterministic evidence needed to replay, compare, and validate external-call results.

The transaction does not need to store the original structured Daml value separately. The structured value is represented by the encoded bytes, and the expected type is known from the interpreted Daml code.

Type identity in transaction metadata is an open design question. It may be useful for auditability, debugging, indexing, and external tooling. But it should be treated carefully because adding it to consensus-critical transaction data increases protocol surface area. A reasonable end-state design is:

- consensus-critical transaction evidence records the bytes and serialization version;
- optional transaction notes or audit metadata may record input/output type identity;
- validation derives the expected type from the Daml package and call site rather than trusting metadata supplied by the service.

This keeps the safety model simple. The external service does not get to decide what type it returned. The Daml program decides the expected type, and the runtime enforces it.

## Error Model

External-call failures should not be reported as generic Daml `UserError`, except when the Daml program itself explicitly calls `error`.

This aligns with upstream maintainer feedback. `UserError` means the Daml programmer deliberately threw a Daml error. External-call runtime failures are different. They should have their own error hierarchy so operators and developers can tell what went wrong.

The final design should distinguish at least these cases:

### Preparation Error

The call could not be prepared before contacting the service.

Examples:

- invalid config hex;
- unsupported input type;
- input contains a disallowed `ContractId`;
- input value cannot be encoded;
- payload exceeds the configured maximum size.

### Execution Error

The service call was attempted, but the service failed or returned an execution-level failure.

Examples:

- unknown extension id;
- unknown function id;
- connection failure;
- timeout;
- non-success service response;
- service returned a declared external-call error.

### Invalid Output Error

The service returned bytes that are not a valid LF value payload.

Examples:

- malformed hex at the host/runtime boundary;
- bytes do not decode under the declared LF value serialization version;
- output exceeds size limits.

### Output Type Mismatch

The service returned a valid LF value, but it does not match the expected Daml output type.

Example:

- Daml expected `Quote`, but the service returned bytes for `Text`;
- Daml expected a record field to be present, but the decoded value does not match that record type;
- Daml expected a variant constructor that belongs to one type, but the output belongs to another type.

### Internal Error

The interpreter reached a state that should be impossible if the compiler and runtime invariants hold.

Examples:

- external-call result recording is missing an enclosing exercise context where one must exist;
- the runtime lost state while resuming interpretation;
- the compiler emitted malformed external-call LF despite validation.

These should be internal errors or crashes in the existing Canton/LF sense, not user-facing Daml errors.

## Security And Privacy Model

Structured external calls create an explicit outbound disclosure boundary.

That should be documented clearly:

> Anything passed as external-call input is disclosed to the configured extension service.

This is true whether the input is raw bytes or a structured Daml record. The structured API makes the feature easier to use, so it also makes accidental disclosure easier if developers do not understand the boundary.

The documentation should tell Daml authors to treat `externalCall` like sending data to an external system. They should not pass confidential fields unless the configured service is meant to receive them.

### Determinism

Canton validation depends on deterministic behavior. For the same extension id, function id, config, and input, the external-call result must be stable according to the feature's validation model.

The system should record enough evidence to validate that behavior. Confirming validators may re-execute and compare external-call results, while other validation paths may replay recorded results according to Canton policy. The exact validation policy belongs to Canton runtime design, but the structured payload design must preserve the same deterministic evidence.

### Malicious Or Broken Services

The runtime must assume that an external service can return invalid data.

The service might return malformed bytes, a value of the wrong type, an oversized payload, or a valid value that is semantically wrong for the application. The runtime can protect against malformed, oversized, and type-mismatched output. It cannot know whether a business answer is semantically correct unless the Daml contract checks it.

Daml authors should still validate business-level properties after receiving the result.

For example, if a price oracle returns a quote, the Daml contract may still need to check:

- the quoted symbol matches the requested symbol;
- the quote has not expired;
- the price is within expected bounds;
- the responding oracle is the expected one;
- the result is signed or otherwise authenticated if the business process requires that.

### ContractId Exclusion

Rejecting `ContractId` in the first design is a security simplification.

It avoids turning external-call services into indirect participants in contract visibility and disclosure logic. If contract references become necessary later, they should be introduced with a dedicated design that explains how visibility, disclosure, activeness, reassignment, and validation work.

### Logging And Observability

External-call logs should avoid dumping raw structured payloads by default.

Because structured input may contain business-sensitive data, logs should prefer:

- extension id;
- function id;
- request id;
- payload size;
- hashes of config/input/output where useful;
- error category;
- serialization version.

Detailed payload logging should be opt-in and clearly marked as sensitive.

### Size And Resource Limits

Structured values can be large. The production feature should define size limits for:

- encoded input value bytes;
- encoded output value bytes;
- config bytes;
- total request envelope size;
- nesting depth, if not already enforced by existing LF value limits.

The cost model should price the interpreter/runtime work and prevent unbounded local resource use. It should not try to price arbitrary remote service execution. Remote service execution is outside the ledger interpreter and should be controlled operationally through timeouts, service configuration, rate limits, and deployment policy.

## Package Upgrades And Schema Evolution

Structured external-call payloads are tied to Daml/LF type identity.

That is a strength because the runtime can validate the output against the expected type. It is also something to handle carefully when packages evolve.

If a Daml package changes the shape of `QuoteRequest` or `Quote`, the external service needs to know which shape it is receiving and which shape it must return. Type identity and package identity in the service envelope help here.

The service should not rely only on a human-readable type name. Two packages may define a type with the same module and data name but different package ids or different field shapes. The envelope should therefore include enough package/type identity to disambiguate the schema.

This does not mean the first design needs a full schema registry. But the protocol should not paint us into a corner. It should include a versioned place for type identity and package identity.

## Config Should Stay BytesHex

For consistency, it is natural to ask whether `config` should also become structured.

The recommendation is: not in the first production design.

The reason is practical. Daml does not have a source-level byte type today, and `config` is conceptually an opaque service-specific byte blob. Keeping it as `BytesHex` avoids expanding the feature into a larger Daml language change.

In the future, we could add a second API:

```daml
externalCallWithConfig
  : forall config input output.
    (Serializable config, Serializable input, Serializable output) =>
    Text -> Text -> config -> input -> Update output
```

But that should be a follow-up. The core feature is structured input and output. Keeping config as hex text is a reasonable and defensible boundary.

## Relationship To The Prototype

The prototype proved the core data path:

- structured Daml input can be encoded into LF value bytes;
- an external service can receive those bytes;
- the service can return LF value bytes;
- the runtime can decode and type-check the response;
- Daml receives a typed output value.

The prototype also exposed what needs to be made production-grade:

- the compiler must preserve concrete input/output types;
- `Serializable` constraints need a principled implementation;
- runtime errors need a dedicated taxonomy;
- LF value serialization versioning must be stable and explicit;
- allowed and disallowed types must be specified;
- tests must cover more than the happy path.

The prototype should therefore be treated as evidence that the design is feasible, not as the code shape we would propose upstream unchanged.

## Recommended End-State Decisions

The following decisions are ready to be approved:

1. The Daml API should support structured `input` and structured `output`.
2. The Daml API should keep `config` as `BytesHex`.
3. The API should require `Serializable input` and `Serializable output`.
4. The first production design should reject `ContractId` in input and output.
5. The runtime should encode/decode input and output using a stable LF value serialization format.
6. The runtime should validate returned output bytes against the expected Daml output type.
7. The service protocol should use a versioned envelope rather than naked bytes.
8. The transaction/protocol layer should record byte-level evidence needed for validation.
9. External-call runtime failures should use a dedicated error hierarchy, not generic `UserError`.
10. The feature should be documented as an explicit outbound disclosure boundary.

## Open Questions

Some questions remain, but they do not block agreement on the final direction.

### Exact Stable Serialization Version

The prototype used a development serialization version. Production needs a stable, versioned LF value payload format.

The design should specify which serialization version is used and how it is feature-gated.

### Type Metadata In Transactions

The service request envelope should include type identity. It is still open whether transaction evidence should also record input/output type identity directly.

The conservative position is to keep consensus-critical transaction evidence byte-oriented and derive types from the Daml package during validation. Optional transaction notes or audit metadata can record type identity later.

### Maps

Maps should probably be allowed if their LF value encoding is stable and canonical.

Before finalizing that, tests should explicitly cover map serialization, deterministic ordering, and type validation.

### ContractId Future

`ContractId` should be rejected in the first production design.

A future design could allow it, but only after addressing visibility, authorization, explicit disclosure, activeness, reassignment, and validation semantics.

### Config As Structured Data

Config should remain `BytesHex` in the first design.

A future API could support structured config if there is enough demand.

### Schema Discovery

The service envelope should carry type identity, but it does not necessarily need to carry a full schema. Services may obtain package/interface information out of band.

The long-term story for service schema discovery remains open.

### Operational Limits

The final implementation needs concrete limits for input size, output size, timeouts, retry behavior, and logging.

Those limits should be part of production hardening.

## Suggested Test Coverage

The production feature should have tests at several levels.

### Daml Compiler Tests

Compiler tests should prove that:

- the public `DA.External.externalCall` API accepts structured input/output;
- `Serializable` constraints work in downstream packages;
- the compiler lowers `externalCall` at the call site with concrete input/output types;
- unsupported types are rejected;
- `ContractId` input/output is rejected.

### LF Validation Tests

LF validation should prove that:

- `BEExternalCall` has the intended polymorphic type;
- only serializable input/output types are accepted;
- disallowed types cannot be used from hand-written LF;
- the feature is gated by the correct LF version.

### Runtime Tests

Runtime tests should cover:

- `Text -> Text`;
- nested records;
- variants;
- enums;
- lists;
- optionals;
- maps, if included;
- malformed output bytes;
- output type mismatch;
- input encode failure;
- disallowed `ContractId`;
- oversized input/output;
- strict config hex validation.

### Canton End-To-End Tests

End-to-end tests should cover:

- submission calls the service;
- validation handles the recorded result correctly;
- repeated calls with the same input produce consistent recorded evidence;
- service failure produces the correct external-call error category;
- observers and confirmers follow the intended replay/re-execution policy;
- transaction evidence records the expected bytes.

## How To Explain This To Non-Specialists

A concise explanation is:

> Today, external calls force Daml developers to manually turn business data into hex strings. We want Daml developers to pass normal Daml records and receive normal Daml records. Canton will serialize those values into stable bytes, send them to the configured extension service, decode the returned bytes, and verify that the result has the expected type. The transaction still records byte-level evidence so validation remains deterministic.

A slightly more technical explanation is:

> The feature introduces a typed boundary at the Daml API while preserving a byte boundary at the Canton protocol layer. The Daml API is ergonomic and type-checked; the protocol evidence remains compact and deterministic.

The key safety message is:

> An external call discloses its input to an external service. The first production design should only allow pure serializable data, not contract references.

## Proposed Approval Statement

If colleagues agree with the direction, the approval can be summarized as:

> We approve structured `external_call` input/output as the target end state. The Daml API should accept serializable input and output values, keep config as `BytesHex`, reject `ContractId` initially, encode/decode payloads using stable LF value serialization, validate service output against the expected Daml type, and record byte-level evidence for Canton validation. Follow-up work may add richer transaction metadata, schema discovery, structured config, or contract-reference support.

This approval would settle the product and architecture direction. After that, the work can be split into upstream-reviewable PRs.

## Related Context

This report is based on:

- the structured I/O prototype work in the Zenith Canton and Daml forks;
- the local prototype report at `external-call-structured-io-design-notes.md`;
- upstream review feedback on the existing external-call PR stack, especially [digital-asset/canton#518](https://github.com/digital-asset/canton/pull/518);
- the local upstream review playbook at `canton-upstream-review-ci-playbook.md`.
