# ADR-0003: UniFFI vs Hand-Written JNI

> Status: **Accepted**
> Date: 2026-05-28
> Context: Phase A/B native crate strategy

## Decision

New Rust crates use UniFFI for Kotlin bindings. Existing 4 hand-written
JNI crates (markdown-parser, regex-transformer, highlight-parser,
office-parsers) stay as-is until touched for other reasons.

## Rationale

### Why UniFFI for new crates

1. **Type-safe codegen**: UDL → Kotlin scaffolding eliminates manual JNI
   marshalling errors (off-by-one in array access, missing null checks).
2. **ABI-stable**: UniFFI's C ABI layer handles Kotlin ↔ Rust type
   conversion; no hand-maintained `#[no_mangle] pub extern "system" fn`.
3. **Cross-platform path**: UniFFI also generates Swift bindings, keeping
   the door open for iOS if KMP ever reaches the native layer.

### Why NOT migrate existing 4 crates

1. **JNI symbols are frozen**: ADR-0001 §5 lists the exact `Java_me_rerere_*`
   symbol names. Changing them requires coordinating Rust + Kotlin + legacy
   package allowlist simultaneously.
2. **Working code**: All 4 crates passed cross-component review V2 + 3 rounds
   of sub-agent fixes. They're production-grade, just not production-connected.
3. **ROI**: Migration effort (~3 days per crate) gains nothing functional.
   Migrate when the crate needs changes for other reasons.

### Coexistence strategy

- New crates: `core/native/<name>/` + UniFFI Gradle plugin
- Old crates: `native/<name>/` + `Exec` task + hand-written `#[no_mangle]`
- Both produce `.so` files into `app/src/main/jniLibs/`
- No conflict: different library names, different JNI symbol prefixes

### Async strategy

- **Short tasks** (< 50ms): synchronous API. Kotlin caller wraps in
  `withContext(Dispatchers.Default)`.
- **Long tasks** (> 50ms): UniFFI `async` with tokio runtime. Kotlin
  gets a suspend function. Cancellation propagated via UniFFI's
  `TaskCallback` cancellation token — test cancellation in CI.

### HARD GATE template (ADR-0004)

Every native crate going to production callers must pass the 5-item gate
from PR #9 §8.3: feature flag, gradual rollout, Crashlytics tagging,
divergence sampling, single-step revert.
