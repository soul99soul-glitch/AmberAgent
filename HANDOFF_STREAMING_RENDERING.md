# Handoff: Streaming Rendering Improvements — Draw-Time Reveal

## Goal

Replace per-frame `AnnotatedString` rebuild with draw-time reveal via `BlendMode.DstOut`, eliminating GC/allocation pressure during streaming text rendering in the Android Kotlin/Compose LLM chat client.

## Branch

`feat/streaming-rendering-improvements` — local only, no push.

## Architecture Overview

### Before (Original)

```
every frame:
  nowNanos changes → remember key invalidates
  → walk entire Markdown AST → build new AnnotatedString with per-codepoint alpha SpanStyle
  → recomposition → relayout → redraw
```

Problem: O(N) AnnotatedString rebuild every frame where N = total text length. Heavy GC pressure during long streaming responses.

### After (Current)

```
on content change only:
  walk Markdown AST → build AnnotatedString ONCE (no alpha) + build offsetMap

every frame (draw phase only, no recomposition/relayout):
  drawWithContent {
    drawContent()                          // text at full alpha
    draw mask paths with BlendMode.DstOut  // erase unrevealed parts
  }
```

Key idea: `AnnotatedString` is stable across frames. The reveal visual is a draw-phase mask that reads `CharRevealController.nowNanos` (a `mutableLongStateOf`) to trigger per-frame draw invalidation without recomposition or relayout.

## Files Changed

### 1. `CharReveal.kt`
Path: `app/src/main/java/me/rerere/rikkahub/ui/components/richtext/CharReveal.kt`

Changes:
- Added `forEachMaskEntry` inline function — iterates over active `RevealEntry` queue, computes `maskAlpha = 1 - (age/effectiveDuration)` per entry, delivers `(contentStart, contentEnd, maskAlpha)` for draw-time consumption
- Changed `RevealEntry` from `private` to `internal` (needed for `OffsetMappingEntry` debugging)

### 2. `Markdown.kt`
Path: `app/src/main/java/me/rerere/rikkahub/ui/components/richtext/Markdown.kt`

This is the core file. Major changes:

#### New types
- `OffsetMappingEntry(contentStart, contentEnd, annotatedStart, annotatedEnd)` — maps a range in source markdown to the corresponding range in the rendered `AnnotatedString`

#### New functions
- `mapContentToAnnotated(offsetMap, contentOffset): Int?` — binary search through `offsetMap` to find the `AnnotatedString` offset for a source offset. Returns `null` if the offset is outside this paragraph's mapped range (Oracle P1 fix).

#### `appendMarkdownNodeContent` signature change
- Added `offsetMap: MutableList<OffsetMappingEntry>?` parameter
- Removed per-codepoint alpha `SpanStyle` logic (was in leaf node branches)
- Now records `OffsetMappingEntry` for every branch that appends visible text:
  - `TEXT` (leaf nodes) — the main path
  - `GFM_AUTOLINK`
  - `INLINE_LINK` (both citation and regular link branches)
  - `AUTOLINK`
  - `CODE_SPAN`
  - `INLINE_MATH` (both `enableLatexRendering` true and false branches)
  - Recursive `else` branch passes `offsetMap` through

#### `Paragraph` composable rewrite

**AnnotatedString construction:**
```kotlin
val (annotatedString, offsetMap) = remember(
    content, enableLatexRendering, onClickUrl, baseColor
) {
    val map = mutableListOf<OffsetMappingEntry>()
    val annotated = buildAnnotatedString { /* walk AST, record map entries */ }
    annotated to map
}
```
Key: `nowNanos`/`revealClock` is NOT in the remember key. This is the core optimization — the AnnotatedString is rebuilt only when content changes.

**TextLayoutResult tracking:**
```kotlin
var textLayoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }
```
NOT keyed to `annotatedString`. Keying caused one frame without mask on every content change → flicker. (See "Current Problem" below.)

**Draw-time mask (current version — saveLayer approach):**
```kotlin
Modifier.drawWithContent {
    val layout = textLayoutResult
    if (layout == null) { drawContent(); return }
    
    drawContext.canvas.saveLayer(Rect(0f, 0f, size.width, size.height), Paint())
    drawContent()
    
    val paragraphContentStart = offsetMap.firstOrNull()?.contentStart ?: 0
    val paragraphContentEnd = offsetMap.lastOrNull()?.contentEnd ?: 0
    
    revealController.forEachMaskEntry { contentStart, contentEnd, maskAlpha ->
        // Clip to paragraph range (handles **bold** spans covering syntax markers)
        val visibleStart = maxOf(contentStart, paragraphContentStart)
        val visibleEnd = minOf(contentEnd, paragraphContentEnd)
        if (visibleStart >= visibleEnd) return@forEachMaskEntry
        
        // Map content offsets to AnnotatedString offsets
        val aStart = mapContentToAnnotated(offsetMap, visibleStart) ?: return
        val aEnd = mapContentToAnnotated(offsetMap, visibleEnd - 1) ?: return
        
        // Get pixel path and draw mask
        val path = layout.multiParagraph.getPathForRange(aStartClamped, aEndExclusive)
        drawPath(color = Color.Black, path = path, alpha = maskAlpha, blendMode = BlendMode.DstOut)
    }
    
    drawContext.canvas.restore()
}
```

## Commits

- `b52c9cc4` — initial draw-time reveal implementation (graphicsLayer + CompositingStrategy.Offscreen)
- Uncommitted: all subsequent fixes (offsetMap gaps, partial overlap clipping, saveLayer migration)

## Oracle Reviews

Two reviews done. All P1 issues fixed:
1. ~~`mapContentToAnnotated` wrong-paragraph fallback~~ → now returns null
2. ~~`offsetMap` missing entries for 5 branches~~ → all text-appending branches now record
3. ~~Partial overlap — `**bold**` reveal entry covering syntax markers was skipped~~ → clip to paragraph range before mapping
4. ~~`textLayoutResult` stale on content change~~ → removed `annotatedString` key from remember

Oracle also noted (P2, not blocking):
- `remember` keys for `annotatedString` omit `colorScheme`, `textStyle`, `density` — theme changes might not propagate until content changes
- Link/code/math mappings compress hidden syntax into visible text range (acceptable — same alpha per entry)
- Path allocation per entry per frame (acceptable for small queue)

## Current Problem: Flickering at Streaming Tail

### Symptom
During streaming, the fade-in/fade-out effect at the tail of the response flickers instead of being smooth. The user confirmed: "原先那个流逝末尾的时候，它不是会有一些淡出淡入的效果吗？现在变成闪烁了" (Previously the streaming tail had a smooth fade-in/fade-out, now it's flickering).

### What We've Tried

1. **Removed `remember(annotatedString)` key on `textLayoutResult`** — the theory was that resetting to null caused one frame without mask. Did not fix the flickering.

2. **Replaced `graphicsLayer(CompositingStrategy.Offscreen)` with `Canvas.saveLayer/restore`** — the theory was that `graphicsLayer` creates a persistent RenderNode whose offscreen buffer doesn't invalidate cleanly when `nowNanos` changes every frame. **This is the current untested version on the phone.**

### Theories for Remaining Flickering

If the `saveLayer` approach also flickers, the root cause is likely NOT the offscreen buffer management. Remaining candidates:

#### Theory A: `nowNanos` time source conflict
`onContentChanged` sets `nowNanos = System.nanoTime()`, while `onFrame` sets `nowNanos = frameNanos` (Choreographer VSYNC). These can differ by a few ms. For entries whose `appearNanos` is close to the current `nowNanos`, this could cause `age` to oscillate between slightly negative and slightly positive → `maskAlpha` jumps between 1.0 and ~0.9 → visible flicker on fresh entries.

**Fix:** In `forEachMaskEntry`, clamp `age = maxOf(0L, now - entry.appearNanos)` instead of letting it go negative. This is a one-line change in `CharReveal.kt`.

#### Theory B: `forEachMaskEntry` draw-phase invalidation not working
`forEachMaskEntry` reads `nowNanos` (a `mutableLongStateOf`) inside `drawWithContent`. If the draw scope's snapshot read observation doesn't properly trigger re-draw when `nowNanos` changes, the mask would be applied with stale alpha values — sometimes correct, sometimes wrong → flicker.

**Test:** Add `invalidate()` or force a state read that's known to work (e.g., read a `mutableStateOf` directly in the `drawWithContent` lambda, outside the inline function).

#### Theory C: Path rendering artifacts
`getPathForRange` returns paths that cover glyph bounds. Drawing these paths with `BlendMode.DstOut` on anti-aliased text might produce subtle edge artifacts that look like flickering at the sub-pixel level.

**Fix:** Replace per-entry paths with a single rectangle per entry (using `getBoundingBox` for start/end chars), accepting less precise masking in exchange for cleaner edges.

### Fallback Plan: Hybrid SpanStyle Approach

If draw-time masking can't be made smooth, use a hybrid:

```kotlin
// Stable base — no alpha, rebuilt only on content change
val (baseAnnotatedString, offsetMap) = remember(content, ...) { ... }

// Per-frame overlay — adds alpha spans only for revealing entries
val annotatedString = if (revealController != null) {
    // Key on nowNanos to get per-frame rebuild
    remember(revealController.nowNanos) {
        buildAnnotatedString {
            append(baseAnnotatedString)  // cheap memcpy
            revealController.forEachMaskEntry { start, end, maskAlpha ->
                val aStart = mapContentToAnnotated(offsetMap, start) ?: return
                val aEnd = mapContentToAnnotated(offsetMap, end - 1) ?: return
                addStyle(
                    SpanStyle(color = baseColor.copy(alpha = 1f - maskAlpha)),
                    start = aStart,
                    end = aEnd + 1
                )
            }
        }
    }
} else baseAnnotatedString
```

Trade-offs:
- ✅ Proven smooth fade (same mechanism as original, just word-level instead of per-codepoint)
- ✅ No `CompositingStrategy.Offscreen` / `saveLayer` / `BlendMode` complexity
- ✅ `append(baseAnnotatedString)` is O(N) memcpy but avoids AST walk — much cheaper than original
- ⚠️ During fade, link/code colors are overridden by `baseColor.copy(alpha=...)` — but fade is only 200ms, barely noticeable
- ⚠️ Still allocates a new `AnnotatedString` per frame, but much less GC than the original per-codepoint approach

## Build & Install

```bash
cd /Users/arquiel/Downloads/AI/rikkashit/rikkahub
JAVA_HOME=$HOME/.gradle/jdks/jetbrains_s_r_o_-21-aarch64-os_x.2/jbrsdk_jcef-21.0.10-osx-aarch64-b1163.110/Contents/Home ./gradlew :app:assembleNotion
ADB=$HOME/Library/Android/sdk/platform-tools/adb
$ADB -s 3B164901CEF00000 install -r app/build/outputs/apk/notion/app-arm64-v8a-notion.apk
```

Package: `me.rerere.amberagent.notion`
Main activity: `me.rerere.rikkahub.RouteActivity`

## Key Design Decisions

| Decision | Rationale |
|----------|-----------|
| `BlendMode.DstOut` (not `DstIn`) | `DstOut`: `dst.α' = dst.α × (1 − src.α)`. Mask alpha from controller IS already `(1 − reveal)`, so we draw with `alpha = maskAlpha` directly. |
| `getPathForRange` (not `getBoundingBox`) | Correctly handles multi-line wraps, surrogate pairs, bidi text, and Placeholder inline content (LaTeX citations). |
| `mapContentToAnnotated` returns nullable | Entries outside current paragraph are skipped entirely. Previous fallback to raw offset caused cross-paragraph masking. |
| Clip to paragraph range before mapping | `**bold**` reveal entries cover syntax markers that aren't in `offsetMap`. Clipping to `[paragraphContentStart, paragraphContentEnd)` ensures we only map the visible part. |
