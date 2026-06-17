# Rust NAPI Evaluation

Rust native reuse is an optimization/evaluation track, not a Phase 0 Chat blocker.

Android currently routes native features through JNI with fallbacks. HarmonyOS should first define ArkTS fallbacks for core Chat and then evaluate NAPI reuse where it provides clear value.

## Initial Target

Start with tokenizer because it has a small API surface:

- Android native source: `../../../native/tokenizer/src/lib.rs`
- Kotlin bridge: `../../../core/llm/src/main/kotlin/app/amber/core/llm/TokenCounterNative.kt`

## Evaluation Paths

1. Direct Rust NAPI binding, if toolchain supports it cleanly.
2. Rust staticlib called by a C++ NAPI shim.
3. ArkTS implementation/fallback when native packaging is too costly.

## Gate

Use the same text fixtures on Android/Rust and HarmonyOS. The token counts must match before any tokenizer NAPI path is considered usable.
