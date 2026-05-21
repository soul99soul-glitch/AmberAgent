# Rust-native spike — Abandoned components

Documents components that were proposed for Rust JNI rewrite and then
abandoned. Kept in `docs/` (not under `native/`) so the lesson survives
even when the half-built code does not.

---

## generative-widget-parser

**Status**: abandoned at Round 1 review. Removed from spike branch
(crate dir + Kotlin adapter both deleted).

### Why

Round 1 sub-agent review flagged a **P0 structural mismatch** between the
abandoned crate's Rust implementation and the JVM
`app/src/main/java/me/rerere/rikkahub/data/ai/generative/GenerativeWidgetParser.kt`
(466 lines).

The Rust impl assumed the wire format was:

````
```show-widget
{...JSON spec...}
<raw HTML or SVG>
```
````

i.e. **fence-bracketed raw widget code**. Production format is actually:

````
```show-widget
{
  "title": "...",
  "renderer": "html",
  "actions": [...],
  "widget_code": "<svg>...</svg>"   ← JSON-escaped string field
}
```
````

The JVM uses `findJsonEnd` to locate the matching `}` then `parseWidgetJson`
to extract every field including the escaped `widget_code` string. The
closer fence is opportunistic.

The two formats are not interconvertible; the spike's benchmark comparison
would have measured Rust parsing a synthetic format that the model never
emits.

### What was missing beyond the format mismatch

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

### Why not just fix it?

Faithfully replicating these is **multi-day work** — out of spike scope.
JVM `GenerativeWidgetParser` evolved with security requirements; matching
each gate in Rust adds significant surface to verify without changing the
underlying perf bottleneck (the JSON spec parse, which `serde_json`
already speeds up vs Kotlin Serialization but by a small constant factor
on the typical ~1-2KB spec).

The honest assessment: **the JVM impl is not a hot path that Rust would
meaningfully improve**, and the security / renderer-dispatch surface makes
a faithful Rust replacement riskier than its perf upside.

### What was deleted

- `native/generative-widget-parser/` (entire crate)
- `app/.../data/ai/generative/nativebridge/GenerativeWidgetParserNative.kt`

If you ever resurrect this work, rebuild from scratch against the JVM
JSON-fields-inside-fence format — don't restore from git history, the
synthetic spike code that lived briefly there is a known dead end.

### If you ever revive this

1. Create a fresh `native/generative-widget-parser/` crate
2. Add to `native/Cargo.toml` workspace members
3. Write `parser.rs` to consume JSON-string-fields-inside-fence with
   `findJsonEnd` semantics, mirroring JVM line-for-line
4. Port every JVM security gate (search the codebase for
   `hasBlockedActionInstruction`)
5. Add fixtures from real production prompts at
   `app/.../data/ai/GenerationPrompts.kt:38` and
   `app/.../data/ai/GenerationHandler.kt:779,878`
6. Wire `containsWidgetFence` / `hasRenderableWidget` JNI exports
7. Re-run sub-agent review

---

## SimpleHtmlBlock

**Status**: never built; recommendation withdrawn before any Rust code
was written.

### Why

Initial mis-read described the file as a "696-line manual HTML scanner".
Actual reading showed `SimpleHtmlBlock.kt` is ~575 lines of Compose UI
rendering wrapping a **single `Jsoup.parse(html)` call** (+ ~120 lines of
inline-style CSS parsing helpers). The rendering portion cannot be moved
to Rust at all (Compose has no Rust equivalent); the actual JVM parsing
that could be Rust-ified is one library call.

Recommendation withdrawn and documented in this file so the original
sizing mistake doesn't get re-litigated next time.

### If you ever want to revisit

Only worth it if you can show Jsoup is a real perf bottleneck on
realistic HTML chat content. Inline CSS helpers (parseInlineStyle /
parseColor / parseFontWeight, ~120 lines) could become a tiny `css-parser`
crate if profile data justifies — but Compose render dominates frame time.

---

## (Future) — append more abandoned components here

Future spike teams should add their own section to this file when
abandoning a component, so the institutional memory survives.
