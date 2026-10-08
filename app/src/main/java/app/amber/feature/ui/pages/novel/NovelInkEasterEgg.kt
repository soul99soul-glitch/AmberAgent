package app.amber.feature.ui.pages.novel

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.amber.agent.BuildConfig
import app.amber.agent.R
import app.amber.feature.ui.theme.LocalAmberTokens
import app.amber.feature.ui.theme.LocalAmberType
import kotlinx.coroutines.delay

/**
 * Tap-streak counter for the hidden ink easter egg: N taps inside a short window
 * reveal it; a longer pause resets the streak. Pure so it stays unit-testable
 * (iOS `NovelAboutSection` parity: 5 taps within 1.2 s).
 */
class InkTapStreak(
    private val tapsToReveal: Int = 5,
    private val windowMs: Long = 1200,
) {
    private var lastTapMs = Long.MIN_VALUE

    /** Current streak depth — drives the nib sinking/ink swelling preview. */
    var sink: Int = 0
        private set

    /** Returns true when this tap completes the streak; the counter then resets. */
    fun tap(nowMs: Long): Boolean {
        sink = if (nowMs - lastTapMs in 0 until windowMs) sink + 1 else 1
        lastTapMs = nowMs
        if (sink < tapsToReveal) return false
        sink = 0
        return true
    }

    /** Called when the streak window elapses without another tap. */
    fun settle(nowMs: Long) {
        if (nowMs - lastTapMs >= windowMs) sink = 0
    }
}

/**
 * Projects-page footer: a quiet nib mark plus version line. Tapping the mark five
 * times in quick succession bursts ink and types out an author's line — discovery
 * rotates through the quote set (iOS `NovelAboutSection` parity).
 */
@Composable
fun NovelInkFooter(modifier: Modifier = Modifier) {
    val tokens = LocalAmberTokens.current
    val type = LocalAmberType.current
    val haptics = LocalHapticFeedback.current
    val motion = animationsEnabled()

    val streak = remember { InkTapStreak() }
    var sinkDepth by remember { mutableIntStateOf(0) }
    var discoveries by remember { mutableIntStateOf(0) }
    var splashTrigger by remember { mutableIntStateOf(0) }
    var quoteIndex by remember { mutableIntStateOf(-1) }
    var typed by remember { mutableIntStateOf(0) }

    // Resolve only the selected quote — six stringResource calls per recompose
    // would fire ~50× during the typewriter pass.
    val quoteRes = remember {
        listOf(
            R.string.novel_ink_quote_1,
            R.string.novel_ink_quote_2,
            R.string.novel_ink_quote_3,
            R.string.novel_ink_quote_4,
            R.string.novel_ink_quote_5,
            R.string.novel_ink_quote_6,
        )
    }
    val quote = quoteIndex.takeIf { it >= 0 }?.let { stringResource(quoteRes[it]) }
    val version = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"

    val nibSink by animateFloatAsState(
        targetValue = sinkDepth * 1.5f,
        animationSpec = spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessMedium),
        label = "nibSink",
    )
    val dropSwell by animateFloatAsState(
        targetValue = 0.6f + sinkDepth * 0.16f,
        animationSpec = spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessMedium),
        label = "inkDrop",
    )

    // A streak that pauses longer than the window settles the nib back.
    LaunchedEffect(sinkDepth) {
        if (sinkDepth <= 0) return@LaunchedEffect
        delay(1200)
        streak.settle(android.os.SystemClock.uptimeMillis())
        sinkDepth = streak.sink
    }

    // Type the revealed quote; a newer reveal cancels the in-flight typing via
    // the keyed effect. `discoveries` (monotonic) is the key — `quoteIndex`
    // cycles and would skip the retype every sixth reveal.
    LaunchedEffect(discoveries) {
        val current = quote ?: return@LaunchedEffect
        if (!motion) {
            typed = current.length
            return@LaunchedEffect
        }
        typed = 0
        for (count in 1..current.length) {
            typed = count
            delay(55)
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(width = 160.dp, height = 96.dp)
                // Hidden affordance — invisible to accessibility services so the
                // footer's only announced content is the version line.
                .clearAndSetSemantics { }
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    val revealed = streak.tap(android.os.SystemClock.uptimeMillis())
                    sinkDepth = streak.sink
                    if (!revealed) return@clickable
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    quoteIndex = discoveries % quoteRes.size
                    discoveries += 1
                    if (motion) splashTrigger += 1
                },
            contentAlignment = Alignment.TopCenter,
        ) {
            NovelInkSplash(
                trigger = splashTrigger,
                modifier = Modifier.align(Alignment.TopCenter),
            )
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.height(10.dp))
                NovelNibMark(
                    size = 44.dp,
                    modifier = Modifier.graphicsLayer { translationY = nibSink.dp.toPx() },
                )
                Spacer(Modifier.height(6.dp))
                Box(
                    Modifier
                        .size(10.dp)
                        .graphicsLayer {
                            scaleX = dropSwell
                            scaleY = dropSwell
                        }
                        .clip(CircleShape)
                        .background(tokens.accent),
                )
            }
        }
        Text(
            "${stringResource(R.string.app_name)} $version",
            style = type.meta,
            color = tokens.ink3,
        )
        if (quote != null) {
            Spacer(Modifier.height(10.dp))
            // The full line reserves its size so typing never resizes the row.
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 32.dp)
                    .clearAndSetSemantics { contentDescription = quote },
            ) {
                Text(
                    quote,
                    style = type.body.copy(fontFamily = FontFamily.Serif),
                    color = tokens.ink2.copy(alpha = 0f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    quote.take(typed),
                    style = type.body.copy(fontFamily = FontFamily.Serif),
                    color = tokens.ink2,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
