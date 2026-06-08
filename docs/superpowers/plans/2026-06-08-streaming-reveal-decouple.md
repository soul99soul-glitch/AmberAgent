# Streaming Reveal Decouple Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the per-codepoint streaming markdown reveal (which welds the animation layer to the parse layer in both directions) with a per-arrival-batch fade where only the unparsed live suffix fades and top-level blocks stabilize on structure alone.

**Architecture:** Today the reveal animation (L4) and the parse/memoization layer (L2/L3) are bidirectionally coupled: the per-frame overlay maps static `AnnotatedString` offsets back to source offsets via `REVEAL_LEAF_TAG` + `LocalMarkdownSourceOffsetBase` (L4→L3), and `StreamingMarkdownParseCache.parse` gates block stabilization on reveal progress via `revealStableEnd` (L3→L4 — parse output becomes a function of frame timing). The new design fades only the live suffix (the gap between L1's displayed text and L3's throttled parse) as a single alpha unit, and stabilizes blocks structurally. Both coupling directions disappear. Gated behind a compile-time `PerfFlags` flag so the legacy path stays until on-device verification, then deleted.

**Tech Stack:** Kotlin, Jetpack Compose, JetBrains `org.intellij.markdown` parser, JUnit4 unit tests, Gradle (`:app:testDebugUnitTest`).

**Decision context (from review):** User chose "彻底解耦,按批次淡入" — accept losing the per-codepoint `baselineShift` float-up effect (`fadingSpanStyle`) in exchange for a clean architecture. The batch unit is the live suffix, which naturally tracks ~one parse-throttle window (200ms) of freshly-streamed text.

---

## Background: why this is small

- **L1 is untouched.** `rememberStreamingDisplayText` (CharReveal.kt:690) keeps pacing the displayed text per frame. The "streaming feel" still comes from L1.
- **L3→L4 cut is one line.** `parse()` already treats `revealStableEnd = null` as "no gating" (Markdown.kt:739, `?: Int.MAX_VALUE`). So decoupling stabilization = pass `null` at the call site when the flag is on. No change to `parse()` internals.
- **L4→L3 cut is local to the Paragraph composable.** Completed blocks already faded as the live suffix while they were the active tail; once stable they just freeze. So no separate per-block fade is needed — only the suffix fades. The suffix range is known directly (`[staticAnnotated.length, end)`), so no `REVEAL_LEAF_TAG` offset table is needed.

## Constraints discovered (do NOT break these)

- **Keep `LocalMarkdownSourceOffsetBase` and `LocalMarkdownSyntheticSuffixStart`.** Code-fence streaming uses them independently of reveal (Markdown.kt:1402, `streamingCodeFenceLiveSuffixFor`). Only stop using `LocalMarkdownSourceOffsetBase` for *paragraph leaf tagging*.
- **Keep a non-null `LocalCharRevealController` sentinel on the active block.** `HighlightCodeBlock.kt:162` uses `LocalCharRevealController.current != null` as the "am I in the active streaming block" signal. `StreamProfilerOverlay.kt` (debug) also reads it.
- **Single flag gates everything.** When `STREAMING_BATCH_REVEAL` is on: parse passes `null` (structural) AND the Paragraph uses the batch fade. When off: fully legacy. Never mix.

## File Structure

| File | Change |
|---|---|
| `app/src/main/java/app/amber/agent/PerfFlags.kt` | Add `STREAMING_BATCH_REVEAL` flag |
| `app/src/main/java/app/amber/feature/ui/components/richtext/Markdown.kt` | Add `applyBatchRevealSuffix` + `STREAMING_BATCH_FADE_MS`; flag-branch the Paragraph reveal build; flag-branch the parse call site |
| `app/src/test/java/app/amber/agent/ui/components/richtext/BatchRevealSuffixTest.kt` | New unit test for `applyBatchRevealSuffix` |
| `app/src/test/java/app/amber/agent/ui/components/richtext/StreamingMarkdownRepairTest.kt` | Add characterization test pinning `parse(revealStableEnd = null)` decoupling |
| `app/src/main/java/app/amber/feature/ui/components/richtext/CharReveal.kt` | (Cleanup phase) reduce `CharRevealController` to a sentinel; keep `rememberStreamingDisplayText` |
| `app/src/main/java/app/amber/feature/ui/components/debug/StreamProfilerOverlay.kt` | (Cleanup phase) repoint off `CharRevealController` internals |
| `app/src/test/java/app/amber/agent/ui/components/richtext/RevealOverlayParityTest.kt` | (Cleanup phase) delete (overlay removed) |
| `app/src/test/java/app/amber/agent/ui/components/richtext/CharRevealControllerTest.kt` | (Cleanup phase) delete or trim to sentinel |

Tasks 1–4 are additive and flag-gated (safe to ship with flag off). Task 5 is on-device validation. Task 6 deletes the legacy path **only after** Task 5 passes.

---

### Task 1: Add the feature flag

**Files:**
- Modify: `app/src/main/java/app/amber/agent/PerfFlags.kt:82` (insert before the closing `}`)

- [ ] **Step 1: Add the flag constant**

Insert after `const val SEARCH_INLINE_IMAGES = true` (line 82), before the closing brace:

```kotlin

    /**
     * Streaming reveal decouple — replace the per-codepoint CharReveal
     * overlay (which maps static AnnotatedString offsets back to source
     * offsets via REVEAL_LEAF_TAG, and gates block stabilization on reveal
     * progress via revealStableEnd) with a per-arrival-batch fade: only the
     * unparsed live suffix fades, as one alpha unit, and top-level blocks
     * stabilize on structure alone. Removes the L3<->L4 coupling. Default
     * keeps the legacy per-codepoint path until on-device verification.
     *
     * Revert if enabled and broken: `git revert <commit-streaming-batch-reveal>`.
     */
    const val STREAMING_BATCH_REVEAL = false
```

- [ ] **Step 2: Verify it compiles**

Run: `cd /Users/arquiel/Downloads/AI/amberagent/main && ./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
cd /Users/arquiel/Downloads/AI/amberagent/main
git add app/src/main/java/app/amber/agent/PerfFlags.kt
git commit -m "feat(stream): add STREAMING_BATCH_REVEAL flag (default off)"
```

---

### Task 2: Pure function `applyBatchRevealSuffix` (TDD)

This is the batch counterpart to `applyRevealOverlay`: fade exactly one contiguous range (the live suffix) as a single alpha unit. Pure and unit-testable.

**Files:**
- Test: `app/src/test/java/app/amber/agent/ui/components/richtext/BatchRevealSuffixTest.kt` (create)
- Modify: `app/src/main/java/app/amber/feature/ui/components/richtext/Markdown.kt` (add function next to `applyRevealOverlay`, ~line 2196)

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/app/amber/agent/ui/components/richtext/BatchRevealSuffixTest.kt`:

```kotlin
package app.amber.feature.ui.components.richtext

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.buildAnnotatedString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class BatchRevealSuffixTest {

    private val base = Color.Black

    @Test
    fun `returns input unchanged when suffix fully revealed`() {
        val combined = buildAnnotatedString { append("settledtail") }
        val out = applyBatchRevealSuffix(combined, staticLength = 7, suffixAlpha = 1f, baseColor = base)
        assertSame(combined, out)
    }

    @Test
    fun `returns input unchanged when baseColor unspecified`() {
        val combined = buildAnnotatedString { append("settledtail") }
        val out = applyBatchRevealSuffix(combined, staticLength = 7, suffixAlpha = 0.5f, baseColor = Color.Unspecified)
        assertSame(combined, out)
    }

    @Test
    fun `returns input unchanged when staticLength at or past end`() {
        val combined = buildAnnotatedString { append("settled") }
        val out = applyBatchRevealSuffix(combined, staticLength = 7, suffixAlpha = 0.3f, baseColor = base)
        assertSame(combined, out)
    }

    @Test
    fun `fades only the suffix range leaving settled prefix opaque`() {
        // static = "settled" [0,7), suffix = "tail" [7,11)
        val combined = buildAnnotatedString { append("settledtail") }
        val out = applyBatchRevealSuffix(combined, staticLength = 7, suffixAlpha = 0.4f, baseColor = base)
        val spans = out.spanStyles
        assertEquals(1, spans.size)
        val span = spans.first()
        assertEquals(7, span.start)
        assertEquals(11, span.end)
        assertEquals(base.copy(alpha = 0.4f), span.item.color)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd /Users/arquiel/Downloads/AI/amberagent/main && ./gradlew :app:testDebugUnitTest --tests "app.amber.feature.ui.components.richtext.BatchRevealSuffixTest"`
Expected: FAIL — `unresolved reference: applyBatchRevealSuffix`

- [ ] **Step 3: Write minimal implementation**

In `Markdown.kt`, immediately above `internal fun applyRevealOverlay(` (line 2197), insert:

```kotlin
/**
 * Per-arrival-batch reveal: the batch counterpart to [applyRevealOverlay].
 * Instead of mapping per-codepoint alpha back to source offsets, it fades
 * exactly one contiguous range — the live suffix appended after the
 * statically-parsed text — as a single alpha unit. [staticLength] is the
 * boundary between settled text (always opaque) and the fading suffix.
 *
 * Returns [combined] unchanged when there's nothing to fade (no usable
 * baseColor, suffix already fully revealed, or the boundary is out of range).
 */
internal fun applyBatchRevealSuffix(
    combined: AnnotatedString,
    staticLength: Int,
    suffixAlpha: Float,
    baseColor: Color,
): AnnotatedString {
    if (baseColor == Color.Unspecified) return combined
    if (suffixAlpha >= 1f) return combined
    if (staticLength < 0 || staticLength >= combined.length) return combined
    return buildAnnotatedString {
        append(combined)
        addStyle(
            style = SpanStyle(color = baseColor.copy(alpha = suffixAlpha.coerceIn(0f, 1f))),
            start = staticLength,
            end = combined.length,
        )
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd /Users/arquiel/Downloads/AI/amberagent/main && ./gradlew :app:testDebugUnitTest --tests "app.amber.feature.ui.components.richtext.BatchRevealSuffixTest"`
Expected: PASS (4 tests)

- [ ] **Step 5: Commit**

```bash
cd /Users/arquiel/Downloads/AI/amberagent/main
git add app/src/main/java/app/amber/feature/ui/components/richtext/Markdown.kt \
        app/src/test/java/app/amber/agent/ui/components/richtext/BatchRevealSuffixTest.kt
git commit -m "feat(stream): add applyBatchRevealSuffix pure helper + tests"
```

---

### Task 3: Wire batch fade into the Paragraph composable (flag-gated)

Replace the legacy `displayAnnotated` / `revealRanges` / `annotatedString` build (Markdown.kt:1722–1776) with an if/else: flag-on uses the batch fade, flag-off keeps the exact legacy path.

**Files:**
- Modify: `app/src/main/java/app/amber/feature/ui/components/richtext/Markdown.kt:1722-1776` (the three `remember` blocks)
- Modify: `app/src/main/java/app/amber/feature/ui/components/richtext/Markdown.kt:377` (add fade-duration constant near `MARKDOWN_STREAMING_PARSE_THROTTLE_MS`)
- Modify: import block (top of `Markdown.kt`) — add `Animatable` / `tween` if absent

- [ ] **Step 1: Add the fade-duration constant**

After `private const val MARKDOWN_STREAMING_PARSE_THROTTLE_MS = 200L` (line 377), insert:

```kotlin

/**
 * Batch reveal fade window (STREAMING_BATCH_REVEAL). Kept strictly below
 * [MARKDOWN_STREAMING_PARSE_THROTTLE_MS] so each batch's suffix reaches
 * alpha≈1 before the next parse tick absorbs it into the settled text —
 * otherwise the absorbed chars would pop from <1 to 1.
 */
private const val STREAMING_BATCH_FADE_MS = 180
```

- [ ] **Step 2: Ensure animation imports exist**

At the top of `Markdown.kt`, confirm these imports are present; add any that are missing:

```kotlin
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.LaunchedEffect
```

(`LaunchedEffect` is almost certainly already imported; `Animatable`/`tween` likely are not.)

- [ ] **Step 3: Replace the reveal build with a flag branch**

Replace lines 1722–1776 — i.e. the block beginning `val displayAnnotated = remember(` and ending at the close of the `val annotatedString = remember(...) { ... }` block (the line before `Text(`) — with:

```kotlin
        val annotatedString = if (app.amber.agent.PerfFlags.STREAMING_BATCH_REVEAL) {
            // Batch reveal (L4 decoupled): only the unparsed live suffix
            // fades, as one alpha unit. No per-leaf REVEAL_LEAF_TAG, no
            // source-offset mapping. The settled prefix stays opaque; it
            // already faded earlier while it was itself the live suffix.
            val combined = remember(staticAnnotated, liveSuffix, baseColor) {
                if (liveSuffix.isEmpty()) {
                    staticAnnotated
                } else {
                    buildAnnotatedString {
                        append(staticAnnotated)
                        append(liveSuffix.replace(BREAK_LINE_REGEX, "\n"))
                    }
                }
            }
            // revealController != null marks the active streaming block; only
            // then is there a suffix to fade. liveSuffixSourceOffset changes
            // once per parse tick, so it is the batch key — a fresh Animatable
            // per batch fades the newly-arrived text from 0→1.
            val streamingTail = revealController != null && liveSuffix.isNotEmpty()
            val suffixAlpha = if (streamingTail) {
                val anim = remember(liveSuffixSourceOffset) { Animatable(0f) }
                LaunchedEffect(liveSuffixSourceOffset) {
                    anim.animateTo(1f, animationSpec = tween(STREAMING_BATCH_FADE_MS))
                }
                anim.value
            } else {
                1f
            }
            remember(combined, staticAnnotated.length, suffixAlpha, baseColor) {
                applyBatchRevealSuffix(
                    combined = combined,
                    staticLength = staticAnnotated.length,
                    suffixAlpha = suffixAlpha,
                    baseColor = baseColor,
                )
            }
        } else {
            val displayAnnotated = remember(
                staticAnnotated,
                liveSuffix,
                liveSuffixSourceOffset,
                baseColor,
            ) {
                if (liveSuffix.isEmpty()) {
                    staticAnnotated
                } else {
                    buildAnnotatedString {
                        append(staticAnnotated)
                        val displaySuffix = liveSuffix.replace(BREAK_LINE_REGEX, "\n")
                        if (baseColor == Color.Unspecified) {
                            append(displaySuffix)
                        } else {
                            pushStringAnnotation(REVEAL_LEAF_TAG, liveSuffixSourceOffset.toString())
                            append(displaySuffix)
                            pop()
                        }
                    }
                }
            }
            val revealRanges = remember(displayAnnotated) {
                displayAnnotated.getStringAnnotations(REVEAL_LEAF_TAG, 0, displayAnnotated.length)
            }
            remember(
                displayAnnotated,
                revealRanges,
                revealClock,
                baseColor,
            ) {
                if (
                    revealController != null &&
                    revealController.hasActiveReveals() &&
                    revealRanges.isNotEmpty()
                ) {
                    applyRevealOverlay(displayAnnotated, revealRanges, revealController, baseColor)
                } else {
                    displayAnnotated
                }
            }
        }
```

(`revealClock`, `revealController`, `baseColor`, `sourceOffsetBase`, `staticAnnotated` remain defined above this block; the `Text(text = annotatedString, ...)` call below is unchanged.)

- [ ] **Step 4: Verify it compiles (flag still off)**

Run: `cd /Users/arquiel/Downloads/AI/amberagent/main && ./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Run the existing reveal tests to confirm no regression (flag off)**

Run: `cd /Users/arquiel/Downloads/AI/amberagent/main && ./gradlew :app:testDebugUnitTest --tests "app.amber.feature.ui.components.richtext.RevealOverlayParityTest" --tests "app.amber.feature.ui.components.richtext.BatchRevealSuffixTest"`
Expected: PASS (legacy path untouched when flag off)

- [ ] **Step 6: Commit**

```bash
cd /Users/arquiel/Downloads/AI/amberagent/main
git add app/src/main/java/app/amber/feature/ui/components/richtext/Markdown.kt
git commit -m "feat(stream): batch-reveal Paragraph build behind STREAMING_BATCH_REVEAL"
```

---

### Task 4: Decouple parse from reveal progress (flag-gated) + characterization test

When the flag is on, the parse call site passes `revealStableEnd = null`, which routes to `parse()`'s existing "no gating" branch — blocks stabilize on structure (`dropLast(1)`), not on animation timing.

**Files:**
- Test: `app/src/test/java/app/amber/agent/ui/components/richtext/StreamingMarkdownRepairTest.kt` (add a test)
- Modify: `app/src/main/java/app/amber/feature/ui/components/richtext/Markdown.kt:942` (the `revealStableEnd` read inside the parse `LaunchedEffect`)

- [ ] **Step 1: Write the characterization test (pins the property the flag relies on)**

Append this test inside the existing test class in `StreamingMarkdownRepairTest.kt` (add imports `org.junit.Assert.assertEquals` / `assertTrue` if not already present):

```kotlin
    @Test
    fun `null revealStableEnd decouples block stabilization from reveal progress`() {
        val content = "alpha\n\nbravo\n\ncharlie"
        // reveal at offset 0 -> nothing has been revealed -> nothing stabilizes
        val gated = StreamingMarkdownParseCache().parse(content, revealStableEnd = 0)
        // null and MAX_VALUE both mean "do not gate on reveal"
        val ungatedNull = StreamingMarkdownParseCache().parse(content, revealStableEnd = null)
        val ungatedMax = StreamingMarkdownParseCache().parse(content, revealStableEnd = Int.MAX_VALUE)

        assertTrue(gated.stableTopLevelBlocks.isEmpty())
        assertTrue(ungatedNull.stableTopLevelBlocks.isNotEmpty())
        assertEquals(ungatedMax.stableTopLevelBlocks.size, ungatedNull.stableTopLevelBlocks.size)
    }
```

- [ ] **Step 2: Run it — it should already PASS (characterizes existing parse behavior)**

Run: `cd /Users/arquiel/Downloads/AI/amberagent/main && ./gradlew :app:testDebugUnitTest --tests "app.amber.feature.ui.components.richtext.StreamingMarkdownRepairTest"`
Expected: PASS. If it FAILS, stop — the assumption that `parse(null)` stabilizes all-but-last is wrong and Task 4's wiring must be reconsidered before proceeding.

- [ ] **Step 3: Flag-branch the parse call site**

In `Markdown.kt`, inside the streaming parse `LaunchedEffect` (the `collectLatest` block), find line 942:

```kotlin
                    val revealStableEnd = updatedRevealStableEnd
```

Replace with:

```kotlin
                    val revealStableEnd = if (app.amber.agent.PerfFlags.STREAMING_BATCH_REVEAL) {
                        null
                    } else {
                        updatedRevealStableEnd
                    }
```

(The existing `streamingParseCache.parse(content = latestContent, revealStableEnd = revealStableEnd)` call below is unchanged.)

- [ ] **Step 4: Verify compile + tests**

Run: `cd /Users/arquiel/Downloads/AI/amberagent/main && ./gradlew :app:compileDebugKotlin && ./gradlew :app:testDebugUnitTest --tests "app.amber.feature.ui.components.richtext.*"`
Expected: BUILD SUCCESSFUL, all richtext unit tests PASS.

- [ ] **Step 5: Commit**

```bash
cd /Users/arquiel/Downloads/AI/amberagent/main
git add app/src/main/java/app/amber/feature/ui/components/richtext/Markdown.kt \
        app/src/test/java/app/amber/agent/ui/components/richtext/StreamingMarkdownRepairTest.kt
git commit -m "feat(stream): structural block stabilization under STREAMING_BATCH_REVEAL"
```

---

### Task 5: On-device validation (manual — user runs)

Flip the flag, build, install, and watch a real streamed response. This is the gate before deleting the legacy path.

- [ ] **Step 1: Enable the flag**

In `PerfFlags.kt`, set `const val STREAMING_BATCH_REVEAL = true`. Rebuild & install:
`cd /Users/arquiel/Downloads/AI/amberagent/main && ./gradlew :app:installDebug`

- [ ] **Step 2: Acceptance checklist** (observe a multi-paragraph + code-block streamed answer)

- [ ] Newly-arriving text fades in smoothly (the trailing ~1 batch / ≤200ms of text).
- [ ] **No pop** when a paragraph/list finishes and the next block starts (the just-settled text does not jump in brightness).
- [ ] No per-character flicker; no reverse-fade (text never gets dimmer after appearing).
- [ ] Code blocks still render their streaming treatment (verifies the `LocalCharRevealController != null` sentinel still fires — `HighlightCodeBlock.kt:162`).
- [ ] Long paragraphs: text keeps emerging via L1 pacing (no stall, no whole-paragraph re-fade on each parse tick).
- [ ] Scrolling a long streamed message stays smooth (no per-frame jank).

- [ ] **Step 3: Tune if needed**

If end-of-batch pop is visible, lower `STREAMING_BATCH_FADE_MS` (e.g. 150). If the fade feels too subtle, raise it toward (but keep `<`) 200. Re-test.

- [ ] **Step 4: Decision** — if the checklist passes, proceed to Task 6. If not, leave the flag `false`, keep the legacy path, and revisit the mechanism (the batch unit / fade timing) before any deletion.

---

### Task 6: Delete the legacy per-codepoint path (ONLY after Task 5 passes)

Flip the default on and remove the now-dead L4↔L2/L3 machinery. Each removal is a separate commit so any single one can be reverted.

**Files & exact removals:**

- [ ] **Step 1: Flip the default**

`PerfFlags.kt` — set `STREAMING_BATCH_REVEAL = true` (now the production path). Commit.

- [ ] **Step 2: Collapse the Paragraph flag branch**

`Markdown.kt` Paragraph (the Task-3 block): delete the `else { ... }` legacy branch entirely; keep only the batch-fade body as the unconditional `annotatedString`. Remove the now-unused `revealClock` val (Markdown.kt:1674) and `sourceOffsetBase` read at line 1675 plus its pass-through to `appendMarkdownNodeContent` (the `sourceOffsetBase = sourceOffsetBase` argument at line 1716). **Do not** remove the `LocalMarkdownSourceOffsetBase` CompositionLocal itself or its `provides` at 1018/1047 — code fences still read it at 1402.

- [ ] **Step 3: Remove per-leaf reveal marking**

`Markdown.kt`:
- In `appendMarkdownNodeContent`, the `LeafASTNode` arm (lines 1934–1946): drop the `canFade` branch; always `append(text)`. Remove the `shouldMarkLeafAsFadeEligible` call.
- Delete `shouldMarkLeafAsFadeEligible` (the `private fun ... : Boolean = baseColor != Color.Unspecified && !trim && text == rawText`, ~line 2155).
- Delete `applyRevealOverlay` (lines 2197–2266) and `fadingSpanStyle` (lines 2192–2195).
- Delete the `REVEAL_LEAF_TAG` const (line 384) and its remaining reference in the (now-deleted) legacy display build. Grep to confirm zero remaining `REVEAL_LEAF_TAG` references before deleting the const.
- Remove the unused `sourceOffsetBase` parameter from `appendMarkdownNodeContent` (line 1906) and its forwarding at recursive call sites.

Run after each: `./gradlew :app:compileDebugKotlin`.

- [ ] **Step 4: Reduce `CharRevealController` to a sentinel**

`CharReveal.kt`: the only remaining consumers are `LocalCharRevealController.current != null` (HighlightCodeBlock) and `StreamProfilerOverlay`. Keep `rememberStreamingDisplayText` (L1) untouched. Replace the per-codepoint `CharRevealController` (queue, `alphaAt`, `stableOffsetExclusive`, `hasActiveReveals`, FPS-degrade, `nowNanos`) with a minimal marker object whose presence means "active streaming tail," and keep `rememberCharRevealController` returning it (non-null while streaming). Remove `revealStableEnd` plumbing if desired (the param can stay as always-null; removing it touches `parse()` signature + the one call site).

- [ ] **Step 5: Update debug overlay**

`StreamProfilerOverlay.kt`: remove or repoint the reveal-queue section (lines ~37, 71) that read `CharRevealController` internals now gone. Keep only what the sentinel can answer (active-or-not).

- [ ] **Step 6: Delete/replace tests**

- Delete `RevealOverlayParityTest.kt` (pins the removed `applyRevealOverlay`).
- Delete or trim `CharRevealControllerTest.kt` (controller reduced to a sentinel).
- Keep `BatchRevealSuffixTest.kt` and the `StreamingMarkdownRepairTest` characterization test.

- [ ] **Step 7: Full build + test sweep**

Run: `cd /Users/arquiel/Downloads/AI/amberagent/main && ./gradlew :app:testDebugUnitTest && ./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL, all tests PASS, zero references to `applyRevealOverlay` / `REVEAL_LEAF_TAG` / `fadingSpanStyle` (`grep -rn` to confirm).

- [ ] **Step 8: Commit**

```bash
cd /Users/arquiel/Downloads/AI/amberagent/main
git add -A
git commit -m "refactor(stream): delete legacy per-codepoint reveal path"
```

---

## Self-Review

- **Spec coverage:** L3→L4 cut → Task 4. L4→L3 cut (offset mapping) → Tasks 2–3 + Task 6 Step 3. "Per-batch fade" → Tasks 2–3. "Accept losing baselineShift float-up" → `fadingSpanStyle` deleted (Task 6 Step 3); the new fade is alpha-only. "Don't switch libraries" → parser untouched. Constraints (code-fence locals, code-block sentinel) → Task 6 Steps 2 & 4 explicitly preserve them.
- **Type consistency:** `applyBatchRevealSuffix(combined: AnnotatedString, staticLength: Int, suffixAlpha: Float, baseColor: Color)` is defined in Task 2 and called with the same names in Task 3. `STREAMING_BATCH_REVEAL` and `STREAMING_BATCH_FADE_MS` spelled consistently throughout.
- **Risk note:** the only behavioral subtlety is end-of-batch pop, bounded by `STREAMING_BATCH_FADE_MS < MARKDOWN_STREAMING_PARSE_THROTTLE_MS` and verified in Task 5. Everything before Task 6 is flag-off-safe and reversible.
