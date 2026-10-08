package app.amber.feature.ui.pages.novel

import android.content.Context
import android.view.accessibility.AccessibilityManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.amber.agent.R
import app.amber.feature.ui.theme.LocalAmberTokens
import app.amber.feature.ui.theme.LocalAmberType
import com.composables.icons.lucide.Flame
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.MoonStar
import com.composables.icons.lucide.Sparkles
import java.util.Calendar
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Which greeting the launch curtain shows; the date rules stay pure and testable. */
enum class NovelLaunchMood(val taglineRes: Int) {
    STANDARD(R.string.novel_curtain_tagline_standard),
    LATE_NIGHT(R.string.novel_curtain_tagline_late_night),
    WRITING_MONTH(R.string.novel_curtain_tagline_writing_month),
    NEW_YEAR(R.string.novel_curtain_tagline_new_year),
}

fun novelLaunchMood(month: Int, day: Int, hour: Int): NovelLaunchMood = when {
    month == 1 && day == 1 -> NovelLaunchMood.NEW_YEAR
    hour < 5 -> NovelLaunchMood.LATE_NIGHT
    month == 11 -> NovelLaunchMood.WRITING_MONTH
    else -> NovelLaunchMood.STANDARD
}

/**
 * Cold-launch overlay for the standalone novel app: the seal stamps onto the page
 * with a ring of ink, the tagline types in, then the curtain lifts. Tap anywhere to
 * skip. Skipped entirely when system animations are off or TalkBack is exploring.
 * (iOS `NovelLaunchCurtain` parity.)
 */
@Composable
fun NovelLaunchCurtain(onFinish: () -> Unit) {
    val tokens = LocalAmberTokens.current
    val type = LocalAmberType.current
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()

    val a11y = remember {
        context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
    }
    if (!animationsEnabled() || a11y?.isTouchExplorationEnabled == true) {
        LaunchedEffect(Unit) { onFinish() }
        return
    }

    val cal = remember { Calendar.getInstance() }
    val mood = remember { novelLaunchMood(cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH), cal.get(Calendar.HOUR_OF_DAY)) }
    val tagline = stringResource(mood.taglineRes)

    val curtainAlpha = remember { Animatable(1f) }
    val curtainLift = remember { Animatable(0f) }
    val sealScale = remember { Animatable(1.8f) }
    val sealRotation = remember { Animatable(-16f) }
    val sealAlpha = remember { Animatable(0f) }
    val ringScale = remember { Animatable(0.5f) }
    val ringAlpha = remember { Animatable(0f) }
    var typed by remember { mutableIntStateOf(0) }
    val playJob = remember { arrayOfNulls<Job>(1) }
    val finishing = remember { booleanArrayOf(false) }

    fun finish() {
        if (finishing[0]) return
        finishing[0] = true
        playJob[0]?.cancel()
        playJob[0] = scope.launch {
            typed = tagline.length
            launch { curtainLift.animateTo(-40f, tween(320, easing = FastOutSlowInEasing)) }
            curtainAlpha.animateTo(0f, tween(320))
            onFinish()
        }
    }

    LaunchedEffect(Unit) {
        playJob[0] = launch {
            if (finishing[0]) return@launch
            // Slam: overshoot anticipation, then a bouncy settle — like a real stamp.
            launch { sealAlpha.animateTo(1f, tween(140)) }
            launch { sealRotation.animateTo(-7f, tween(240, easing = FastOutSlowInEasing)) }
            sealScale.animateTo(0.92f, tween(240, easing = FastOutSlowInEasing))
            launch { sealRotation.animateTo(-6f, spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMedium)) }
            sealScale.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMedium))
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            // Ink bleed ring.
            launch { ringAlpha.animateTo(1f, tween(80)) }
            launch { ringScale.animateTo(1.3f, tween(600, easing = FastOutSlowInEasing)) }
            launch { delay(80); ringAlpha.animateTo(0f, tween(700)) }
            // Typewriter.
            for (count in 1..tagline.length) {
                typed = count
                delay(45)
            }
            delay(420)
            finish()
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                alpha = curtainAlpha.value
                translationY = curtainLift.value * density
            }
            .background(tokens.bg)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { finish() },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(32.dp),
            modifier = Modifier.padding(horizontal = 32.dp),
        ) {
            Box(Modifier.height(190.dp), contentAlignment = Alignment.Center) {
                // Soft ink-bleed ring — a radial gradient stands in for iOS's blur
                // (Modifier.blur is RenderEffect-only, API 31+; minSdk is 26).
                Box(
                    Modifier
                        .size(190.dp)
                        .graphicsLayer {
                            scaleX = ringScale.value
                            scaleY = ringScale.value
                            alpha = ringAlpha.value
                        }
                        .background(
                            Brush.radialGradient(
                                listOf(
                                    tokens.accent.copy(alpha = 0.20f),
                                    tokens.accent.copy(alpha = 0.10f),
                                    Color.Transparent,
                                ),
                            ),
                            CircleShape,
                        ),
                )
                NovelSealMark(
                    size = 120.dp,
                    modifier = Modifier.graphicsLayer {
                        scaleX = sealScale.value
                        scaleY = sealScale.value
                        rotationZ = sealRotation.value
                        alpha = sealAlpha.value
                    },
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.clearAndSetSemantics { contentDescription = tagline },
            ) {
                when (mood) {
                    NovelLaunchMood.LATE_NIGHT -> Icon(Lucide.MoonStar, null, Modifier.size(16.dp), tint = tokens.accent)
                    NovelLaunchMood.WRITING_MONTH -> Icon(Lucide.Flame, null, Modifier.size(16.dp), tint = tokens.accent)
                    NovelLaunchMood.NEW_YEAR -> Icon(Lucide.Sparkles, null, Modifier.size(16.dp), tint = tokens.accent)
                    NovelLaunchMood.STANDARD -> Unit
                }
                // The full line reserves its size so typing never shifts the row.
                Box {
                    Text(
                        tagline,
                        style = type.body.copy(fontFamily = FontFamily.Serif),
                        color = tokens.ink2.copy(alpha = 0f),
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        tagline.take(typed),
                        style = type.body.copy(fontFamily = FontFamily.Serif),
                        color = tokens.ink2,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}
