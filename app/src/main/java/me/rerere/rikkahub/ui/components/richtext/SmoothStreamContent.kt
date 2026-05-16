package me.rerere.rikkahub.ui.components.richtext

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos

// Phase B / B6 — CPS-throttle smoother for streaming markdown content.
//
// Adapts LobeHub `useSmoothStreamContent`'s "decouple input from output
// rate" model to Compose. The key insight: when an upstream like
// DeepSeek emits a 100-char burst then idles for 200ms, the user
// shouldn't see "100 chars dump + freeze" — they should see a steady
// stream pacing toward the latest target. Char-level fade (B1/B2)
// alone smooths the *visual* edge but doesn't pace the *output* — a
// burst still pops as a wall.
//
// How it works:
//
//   upstream content (chunk-flushed) → snapshotFlow content
//      ↓ EMA-track arrival CPS
//      ↓ RAF loop: each frame, release N chars where N is determined
//        by the smoother's chosen displayCPS × frameDeltaMs/1000.
//        displayCPS adapts:
//          - normally tracks arrival CPS (no buffer build-up)
//          - if backlog * 1000 / displayCPS > targetBufferMs, accelerate
//            up to maxCps to catch up
//          - clamped to [minCps, maxCps]
//      ↓ append to displayedContent (a String built from a codepoint
//        prefix of the latest input)
//
// Codepoint-aware: indices walk by Character.charCount(cp) so emoji
// and CJK Ext-B (surrogate pairs) never get split mid-codepoint.
//
// Idle short-circuit: when displayedCount == targetCount, the RAF
// loop continues to no-op cheaply (the withFrameNanos callback returns
// without state writes), so MarkdownBlock's remember keys upstream
// don't invalidate every frame just because B6 is alive.
//
// Pairs with B1+B5 reveal: each char released here flows into
// CharRevealController.onContentChanged → fades over preset's
// baseRevealDurationMs. Two layers, one pacing (output cadence) and
// one easing (per-char alpha), composed.

private const val MIN_PROGRESS_PER_FRAME_CHARS = 1

/**
 * CPS-throttled smoother. Returns a String that grows toward [content]
 * at the preset's controlled rate. When [streaming] is false the
 * function is a pass-through (returns [content] verbatim) — no extra
 * state, no RAF loop.
 *
 * Caller integration: replace `content` with the return value at the
 * MarkdownBlock entry, then everything downstream (markdown parse,
 * char-level reveal) sees the throttled prefix instead of the raw
 * upstream string.
 */
@Composable
fun rememberSmoothStreamedContent(
    content: String,
    streaming: Boolean,
    preset: StreamRevealPreset = LocalStreamRevealPreset.current,
): String {
    if (!streaming) return content
    val config = preset.smoothing

    // Codepoint count of the currently-displayed prefix.
    val displayedCpCount = remember(preset) { mutableIntStateOf(0) }
    // The full upstream string's codepoint count, updated when content grows.
    val targetCpCount = remember(preset) { mutableIntStateOf(0) }

    // EMAs for adaptive CPS.
    val arrivalCps = remember(preset) {
        mutableFloatStateOf(config.defaultCps.toFloat())
    }
    val displayCps = remember(preset) {
        mutableFloatStateOf(config.defaultCps.toFloat())
    }
    val lastInputAtNanos = remember(preset) { mutableLongStateOf(0L) }
    val lastInputCpCount = remember(preset) { mutableIntStateOf(0) }
    val lastFrameNanos = remember(preset) { mutableLongStateOf(0L) }

    val updatedContent by rememberUpdatedState(content)

    // Watch upstream — update target + arrival-rate EMA.
    LaunchedEffect(preset) {
        snapshotFlow { updatedContent }
            .collect { latest ->
                val now = System.nanoTime()
                val newCpCount = countCodePoints(latest)
                if (newCpCount < targetCpCount.intValue) {
                    // Truncation (rare — branch switch / regen). Reset.
                    displayedCpCount.intValue = 0
                    lastInputCpCount.intValue = 0
                    lastInputAtNanos.longValue = 0L
                    arrivalCps.floatValue = config.defaultCps.toFloat()
                    displayCps.floatValue = config.defaultCps.toFloat()
                }
                val deltaCp = newCpCount - lastInputCpCount.intValue
                if (deltaCp > 0 && lastInputAtNanos.longValue > 0L) {
                    val deltaSec = (now - lastInputAtNanos.longValue) / 1e9f
                    if (deltaSec > 0f) {
                        val instantCps = deltaCp / deltaSec
                        // EMA toward instant rate.
                        arrivalCps.floatValue =
                            arrivalCps.floatValue * (1f - config.emaAlpha) +
                                instantCps * config.emaAlpha
                    }
                }
                lastInputCpCount.intValue = newCpCount
                lastInputAtNanos.longValue = now
                targetCpCount.intValue = newCpCount
            }
    }

    // Per-frame release loop.
    LaunchedEffect(preset) {
        while (true) {
            withFrameNanos { frame ->
                val target = targetCpCount.intValue
                val current = displayedCpCount.intValue
                if (current >= target) {
                    // Caught up — no work, but keep the loop alive so
                    // fresh chunks resume release immediately.
                    lastFrameNanos.longValue = frame
                    return@withFrameNanos
                }
                if (lastFrameNanos.longValue == 0L) {
                    lastFrameNanos.longValue = frame
                    return@withFrameNanos
                }
                val deltaSec = (frame - lastFrameNanos.longValue) / 1e9f
                lastFrameNanos.longValue = frame

                // Adaptive CPS: when backlog would take longer than
                // targetBufferMs at the current arrival rate, push the
                // display CPS upward toward maxCps so we close the gap.
                val backlogCp = (target - current).toFloat()
                val arrival = arrivalCps.floatValue.coerceAtLeast(1f)
                val baseDesired = arrival
                val backlogTimeMs = backlogCp * 1000f / arrival
                val pushFactor = if (backlogTimeMs > config.targetBufferMs) {
                    // Quadratic-ish push: more behind → harder push,
                    // capped by maxCps clamp below.
                    1f + (backlogTimeMs / config.targetBufferMs - 1f) * 0.5f
                } else 1f
                val desiredCps = (baseDesired * pushFactor)
                    .coerceIn(config.minCps.toFloat(), config.maxCps.toFloat())
                // Smooth the display CPS itself so it doesn't whiplash.
                displayCps.floatValue =
                    displayCps.floatValue * (1f - config.emaAlpha) +
                        desiredCps * config.emaAlpha

                val releaseFloat = displayCps.floatValue * deltaSec
                val release = releaseFloat.toInt().coerceAtLeast(MIN_PROGRESS_PER_FRAME_CHARS)
                val newCount = (current + release).coerceAtMost(target)
                if (newCount != current) {
                    displayedCpCount.intValue = newCount
                }
            }
        }
    }

    // Compute the displayed substring. derivedStateOf so reads in
    // descendants only invalidate when the prefix length actually
    // changes, not on every input or CPS adjustment.
    val displayedString by remember(preset) {
        derivedStateOf {
            val target = targetCpCount.intValue
            val displayed = displayedCpCount.intValue
            val source = updatedContent
            when {
                source.isEmpty() -> ""
                displayed >= target -> source
                displayed <= 0 -> ""
                else -> source.substringByCodePoints(displayed)
            }
        }
    }
    return displayedString
}

/** Codepoint count (handles surrogate pairs). */
private fun countCodePoints(text: String): Int {
    if (text.isEmpty()) return 0
    var count = 0
    var i = 0
    while (i < text.length) {
        i += Character.charCount(text.codePointAt(i))
        count++
    }
    return count
}

/**
 * Substring by codepoint count rather than UTF-16 char count, so a
 * cut never lands inside a surrogate pair. Returns the prefix
 * containing the first [codePointCount] codepoints.
 */
private fun String.substringByCodePoints(codePointCount: Int): String {
    if (codePointCount <= 0) return ""
    var seen = 0
    var i = 0
    while (i < length && seen < codePointCount) {
        i += Character.charCount(codePointAt(i))
        seen++
    }
    return substring(0, i)
}
