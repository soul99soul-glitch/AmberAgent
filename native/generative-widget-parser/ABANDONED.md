# ABANDONED: generative-widget-parser

**Status**: removed from workspace members at Round 1 review.

## Why

Round 1 sub-agent review flagged a **P0 structural mismatch** between this
crate's Rust implementation and the JVM
`app/src/main/java/me/rerere/rikkahub/data/ai/generative/GenerativeWidgetParser.kt`
(466 lines).

The Rust impl assumed the wire format was:

```
```show-widget
{...JSON spec...}
<raw HTML or SVG>
```
```

i.e. **fence-bracketed raw widget code**. Production format is actually:

```
```show-widget
{
  "title": "...",
  "renderer": "html",
  "actions": [...],
  "widget_code": "<svg>...</svg>"   ← JSON-escaped string field
}
```
```

The JVM uses `findJsonEnd` to locate the matching `}` then
`parseWidgetJson` to extract every field including the escaped
`widget_code` string. The closer fence is opportunistic.

The two formats are not interconvertible; the spike's benchmark
comparison would have measured Rust parsing a synthetic format that
the model never emits.

## What was missing beyond the format mismatch

1. **Security**: `MAX_ACTIONS = 3` cap, label 1..20 / instruction 1..240
   length validation, `hasBlockedActionInstruction` (blocks `<system`,
   `ignore previous`, raw URLs in action instructions), `placeholderPhrases`
   rejection list, ID sanitization.
2. **Multi-renderer dispatch**: `renderer != "html"` delegates to
   `GenerativeWidgetRenderer.render(renderer, spec)` which currently
   handles `vchart` / `slides` / `chart` / `diagram` codepaths.
3. **Streaming-tolerant JSON parser**: `extractJsonStringValue` decodes
   `\u` escapes from possibly-truncated JSON; `parsePartialWidget`
   synthesizes SVG placeholders for streaming `slides` / `vchart` widgets.
4. **`containsWidgetFence` / `hasRenderableWidget` helpers**: used by
   `ChatMessageRenderers.kt:129`, `ChatMessageVirtualItems.kt:178`,
   `ChatMessageMessagePartsBlock.kt:116`, `GenerationHandler.kt:746`,
   `GenerationHandler.kt:856` — no Rust equivalents shipped.

## Why not just fix it?

Faithfully replicating these is **multi-day work** — out of spike scope.
JVM `GenerativeWidgetParser` evolved with security requirements; matching
each gate in Rust adds significant surface to verify without changing
the underlying perf bottleneck (the JSON spec parse, which `serde_json`
already speeds up vs Kotlin Serialization but by a small constant factor
on the typical ~1-2KB spec).

The honest assessment: **the JVM impl is not a hot path that Rust would
meaningfully improve**, and the security / renderer-dispatch surface
makes a faithful Rust replacement riskier than its perf upside.

## What lives here

- `Cargo.toml` — the crate manifest (decoupled from workspace via
  `Cargo.toml` member comment)
- `src/lib.rs` + `src/parser.rs` — the synthetic-format implementation
  (kept as a learning artifact, not compiled)
- This file

The Kotlin adapter at
`app/src/main/java/me/rerere/rikkahub/data/ai/generative/nativebridge/GenerativeWidgetParserNative.kt`
is retained too — if a future faithful rewrite happens, the JNI surface
+ packed binary decoder are still valid scaffolding.

## If you ever revive this

1. Re-add to `native/Cargo.toml` workspace members
2. Rewrite `parser.rs` to consume JSON-string-fields-inside-fence with
   `findJsonEnd` semantics
3. Port every JVM security gate (search this dir for `hasBlockedActionInstruction`)
4. Add fixtures from real production prompts at
   `app/.../data/ai/GenerationPrompts.kt:38` and
   `app/.../data/ai/GenerationHandler.kt:779,878`
5. Wire `containsWidgetFence` / `hasRenderableWidget` exports
6. Re-run sub-agent review
