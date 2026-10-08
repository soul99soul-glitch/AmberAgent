package app.amber.feature.ui.pages.board

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.amber.agent.R
import app.amber.agent.StandaloneSurfaces
import app.amber.feature.board.hotlist.deepread.DeepReadMoments
import app.amber.feature.ui.theme.AmberTokens
import app.amber.feature.ui.theme.LocalAmberTokens
import app.amber.feature.ui.theme.LocalAmberType
import app.amber.feature.ui.theme.NotoSerifSC
import app.amber.feature.ui.pages.novel.animationsEnabled
import com.composables.icons.lucide.BookMarked
import com.composables.icons.lucide.Flag
import com.composables.icons.lucide.Flame
import com.composables.icons.lucide.Lamp
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.MoonStar
import com.composables.icons.lucide.Snowflake
import com.composables.icons.lucide.Sparkles
import kotlin.math.sqrt
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A cinnabar seal: double bordered block, two characters set vertically like a
 * traditional 印章 (iOS `DeepReadInkStamp` parity).
 */
@Composable
fun DeepReadInkStamp(text: String, size: Dp, modifier: Modifier = Modifier) {
    val accent = LocalAmberTokens.current.accent
    Box(
        modifier
            .size(size)
            .graphicsLayer { alpha = 0.88f }
            .border(size * 0.07f, accent, RoundedCornerShape(size * 0.14f)),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(size * 0.8f)
                .border(size * 0.02f, accent, RoundedCornerShape(size * 0.08f)),
        )
        Text(
            text.toList().joinToString("\n"),
            fontFamily = FontFamily.Serif,
            fontWeight = FontWeight.ExtraBold,
            fontSize = (size.value * 0.3f).sp,
            lineHeight = (size.value * 0.3f).sp,
            color = accent,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * Slams a stamp down when [trigger] goes above zero — an overshoot anticipation,
 * a bouncy settle and a small rotation, like a real seal hitting paper
 * (iOS `DeepReadStampSlam` parity). Stays on the page afterwards.
 */
fun Modifier.deepReadStampSlam(trigger: Int): Modifier = composed {
    val scale = remember { Animatable(1f) }
    val rotation = remember { Animatable(-10f) }
    val alpha = remember { Animatable(0f) }
    val haptics = LocalHapticFeedback.current
    val motion = animationsEnabled()
    LaunchedEffect(trigger) {
        if (trigger <= 0) return@LaunchedEffect
        if (!motion) {
            rotation.snapTo(-10f)
            scale.snapTo(1f)
            alpha.animateTo(1f, tween(250))
            return@LaunchedEffect
        }
        scale.snapTo(2.6f)
        rotation.snapTo(8f)
        alpha.snapTo(0f)
        launch { alpha.animateTo(1f, tween(140)) }
        launch { rotation.animateTo(-10f, spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMedium)) }
        scale.animateTo(0.92f, tween(180, easing = FastOutSlowInEasing))
        scale.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMedium))
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
    }
    graphicsLayer {
        scaleX = scale.value
        scaleY = scale.value
        rotationZ = rotation.value
        this.alpha = alpha.value
    }
}

/**
 * Warm-paper grain plus a late-night desk-lamp glow, drawn once into a cached
 * bitmap so scroll frames stay cheap (iOS `DeepReadPaperBackground` parity).
 */
fun Modifier.deepReadPaper(night: Boolean, tokens: AmberTokens): Modifier = composed {
    val density = LocalDensity.current
    // The grain bitmap is built off the draw pass — a full-screen ARGB bitmap is
    // ~10 MB and must not allocate on the first frame's draw call.
    var sizePx by remember { mutableStateOf(IntSize.Zero) }
    val grain by produceState<ImageBitmap?>(null, sizePx, tokens.ink) {
        if (sizePx.width <= 0 || sizePx.height <= 0) return@produceState
        value = withContext(Dispatchers.Default) {
            renderGrain(sizePx.width.toFloat(), sizePx.height.toFloat(), tokens.ink)
        }
    }
    val lampOn = remember { mutableIntStateOf(0) }
    val lampAlpha by animateFloatAsState(
        targetValue = if (night && lampOn.intValue > 0) (if (tokens.isDark) 0.26f else 0.20f) else 0f,
        animationSpec = if (animationsEnabled()) tween(1600, delayMillis = 200, easing = FastOutSlowInEasing) else tween(300),
        label = "deepReadLamp",
    )
    LaunchedEffect(night) {
        if (night && lampOn.intValue == 0) lampOn.intValue = 1
    }
    onSizeChanged { sizePx = it }
        .drawBehind {
            grain?.let { drawImage(it) }
            if (lampAlpha > 0f) {
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(LampGlow.copy(alpha = lampAlpha), Color.Transparent),
                        center = Offset(
                            size.width * 0.82f,
                            // Start under the opaque top bar, where the halo
                            // would otherwise be cut off mid-peak.
                            with(density) { 64.dp.toPx() },
                        ),
                        radius = with(density) { 520.dp.toPx() },
                    ),
                )
            }
        }
}

private val LampGlow = Color(1f, 0.78f, 0.45f)

/**
 * Standalone DeepRead sets its editorial voice in the bundled CJK serif —
 * the counterpart of iOS's `design: .serif` headlines. The bundled face ships
 * one weight, so hierarchy comes from size and tracking, not fontWeight. The
 * full app keeps its terminal-modern sans; only the dedicated reading product
 * switches.
 */
val deepReadEditorialSerif: FontFamily?
    get() = if (StandaloneSurfaces.current == StandaloneSurfaces.DEEP_READ) NotoSerifSC else null

/** Fixed-seed noise: ~1px ink dots at up-to-5% alpha, matching the iOS grain field. */
private fun renderGrain(width: Float, height: Float, ink: Color): ImageBitmap {
    val bitmap = ImageBitmap(width.toInt(), height.toInt())
    val canvas = androidx.compose.ui.graphics.Canvas(bitmap)
    // Same fixed seed as the iOS grain field so both surfaces share the speckle feel.
    val random = Random(0x9E3779B97F4A7C15uL.toLong())
    val paint = androidx.compose.ui.graphics.Paint()
    val count = ((width * height) / 260f).toInt().coerceAtMost(6000)
    repeat(count) {
        paint.color = ink.copy(alpha = 0.05f * random.nextFloat())
        canvas.drawCircle(
            Offset(random.nextFloat() * width, random.nextFloat() * height),
            radius = 0.55f,
            paint = paint,
        )
    }
    return bitmap
}

/**
 * DeepRead press: a softer settle than the system default plus a faint ink
 * wash over the paper — iOS's pressable paper card (scale + brightness drop)
 * adapted to the flat index rows.
 */
@Composable
fun Modifier.pressableDeepRead(onClick: () -> Unit): Modifier {
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val motion = animationsEnabled()
    val scale by animateFloatAsState(
        if (pressed) 0.978f else 1f,
        if (motion) spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMediumLow) else snap(),
        label = "drPressScale",
    )
    val wash by animateFloatAsState(
        if (pressed) 0.055f else 0f,
        if (motion) tween(140) else snap(),
        label = "drPressWash",
    )
    val ink = LocalAmberTokens.current.ink
    val haptics = LocalHapticFeedback.current
    return this
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .drawBehind { if (wash > 0f) drawRect(ink.copy(alpha = wash)) }
        .clickable(
            interactionSource = interaction,
            indication = null,
            role = androidx.compose.ui.semantics.Role.Button,
        ) {
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            onClick()
        }
}

/**
 * Row entrance: rises from below with a stagger. Only the first rows wait, so
 * long lists stay instant (iOS `deepReadEntrance` parity).
 */
fun Modifier.deepReadEntrance(index: Int, shown: Boolean): Modifier = composed {
    val lift = remember { Animatable(26f) }
    val alpha = remember { Animatable(0f) }
    // Rows composed while scrolling in (shown already true at first composition)
    // appear instantly — replaying the stagger there would flash them blank.
    val skipAnim = remember { shown }
    LaunchedEffect(shown) {
        if (!shown) return@LaunchedEffect
        if (skipAnim || !animationsEnabled()) {
            lift.snapTo(0f)
            alpha.snapTo(1f)
            return@LaunchedEffect
        }
        // Only the first rows wait, so long lists stay instant.
        delay(minOf(index, 8) * 50L)
        launch { lift.animateTo(0f, spring(dampingRatio = 0.82f, stiffness = Spring.StiffnessLow)) }
        alpha.animateTo(1f, tween(320))
    }
    graphicsLayer {
        this.alpha = alpha.value
        translationY = lift.value * density
    }
}

private fun DeepReadMoments.Festival.labelRes(): Int = when (this) {
    DeepReadMoments.Festival.LUNAR_NEW_YEAR -> R.string.deep_read_moment_lunar_new_year
    DeepReadMoments.Festival.LANTERN -> R.string.deep_read_moment_lantern
    DeepReadMoments.Festival.MID_AUTUMN -> R.string.deep_read_moment_mid_autumn
    DeepReadMoments.Festival.NEW_YEAR -> R.string.deep_read_moment_new_year
    DeepReadMoments.Festival.BOOK_DAY -> R.string.deep_read_moment_book_day
    DeepReadMoments.Festival.NATIONAL_DAY -> R.string.deep_read_moment_national_day
    DeepReadMoments.Festival.CHRISTMAS -> R.string.deep_read_moment_christmas
}

private fun DeepReadMoments.Festival.icon() = when (this) {
    DeepReadMoments.Festival.LUNAR_NEW_YEAR -> Lucide.Flame
    DeepReadMoments.Festival.LANTERN -> Lucide.Lamp
    DeepReadMoments.Festival.MID_AUTUMN -> Lucide.MoonStar
    DeepReadMoments.Festival.NEW_YEAR -> Lucide.Sparkles
    DeepReadMoments.Festival.BOOK_DAY -> Lucide.BookMarked
    DeepReadMoments.Festival.NATIONAL_DAY -> Lucide.Flag
    DeepReadMoments.Festival.CHRISTMAS -> Lucide.Snowflake
}

/** Small accent badge marking a reading festival on the discovery surface. */
@Composable
fun DeepReadFestivalBadge(festival: DeepReadMoments.Festival, modifier: Modifier = Modifier) {
    val tokens = LocalAmberTokens.current
    Row(
        modifier,
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(festival.icon(), contentDescription = null, modifier = Modifier.size(14.dp), tint = tokens.accent)
        Text(
            stringResource(festival.labelRes()),
            style = LocalAmberType.current.meta.copy(fontSize = 11.sp),
            color = tokens.accent,
            modifier = Modifier.padding(start = 6.dp),
        )
    }
}

/**
 * Accelerometer shake → [onShake], debounced. Registered only while [enabled];
 * a missing sensor silently disables the gesture.
 */
@Composable
fun rememberShakeDetector(enabled: Boolean, onShake: () -> Unit) {
    val context = LocalContext.current
    val latest = rememberUpdatedState(onShake)
    DisposableEffect(enabled) {
        if (!enabled) return@DisposableEffect onDispose { }
        val manager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
            ?: return@DisposableEffect onDispose { }
        val sensor = manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            ?: return@DisposableEffect onDispose { }
        var lastMs = 0L
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val x = event.values[0]
                val y = event.values[1]
                val z = event.values[2]
                val g = sqrt(x * x + y * y + z * z) / SensorManager.GRAVITY_EARTH
                val now = SystemClock.uptimeMillis()
                if (g > 2.6f && now - lastMs > 900) {
                    lastMs = now
                    latest.value()
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
        onDispose { manager.unregisterListener(listener) }
    }
}
