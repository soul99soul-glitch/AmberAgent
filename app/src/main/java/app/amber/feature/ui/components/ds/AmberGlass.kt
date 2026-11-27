package app.amber.feature.ui.components.ds

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import app.amber.feature.ui.theme.AmberTokens
import app.amber.feature.ui.theme.LocalAmberTokens
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Where a glass surface sits in the floating chrome stack.
 *
 * - [Control]: 44–48dp floating buttons, the composer pill and the host pill.
 * - [Chip]: small labels resting on another element (the persona name pill).
 * - [Well]: a track that holds a [Lens] (the 对话 / 任务 / 中心 switcher).
 * - [Lens]: the selected item inside a [Well].
 * - [Panel]: popovers and sheets that float over the conversation.
 */
enum class AmberGlassRole { Control, Chip, Well, Lens, Panel }

private data class GlassShadow(val radius: Float, val y: Float, val alpha: Float)

private data class GlassRecipe(
    val ambient: GlassShadow?,
    val contact: GlassShadow?,
    /** Peak alpha of the specular arc along the top of the curve. */
    val rimTop: Float,
    /** Light caught by the lower lip, read as the glass refracting the page under it. */
    val rimBottom: Float,
    /** Hairline along the lower half that seats the silhouette on the paper. */
    val edge: Float,
    /** Body opacity: below 1 the warm page shows through, as it would through glass. */
    val bodyAlpha: Float,
)

private fun recipe(role: AmberGlassRole, dark: Boolean): GlassRecipe = when (role) {
    AmberGlassRole.Control -> if (dark) {
        GlassRecipe(GlassShadow(7f, 2.5f, .18f), GlassShadow(1.2f, .75f, .20f), .14f, .03f, .11f, .94f)
    } else {
        GlassRecipe(GlassShadow(7f, 2.2f, .042f), GlassShadow(1.4f, .7f, .058f), .82f, .14f, .095f, .95f)
    }
    AmberGlassRole.Chip -> if (dark) {
        GlassRecipe(GlassShadow(5f, 2f, .16f), GlassShadow(1f, .5f, .18f), .13f, .02f, .10f, .94f)
    } else {
        GlassRecipe(GlassShadow(5.5f, 1.8f, .036f), GlassShadow(1.2f, .6f, .050f), .76f, .12f, .090f, .94f)
    }
    AmberGlassRole.Well -> if (dark) {
        GlassRecipe(GlassShadow(4f, 1.5f, .12f), GlassShadow(1f, .5f, .14f), .07f, .04f, .09f, .90f)
    } else {
        GlassRecipe(GlassShadow(3.5f, 1.2f, .016f), GlassShadow(1f, .5f, .028f), .36f, .16f, .075f, .90f)
    }
    AmberGlassRole.Lens -> if (dark) {
        GlassRecipe(GlassShadow(3.5f, 1f, .18f), GlassShadow(1f, .5f, .20f), .18f, .02f, .12f, .96f)
    } else {
        GlassRecipe(GlassShadow(4.5f, 1.4f, .036f), GlassShadow(1.1f, .55f, .052f), .86f, .14f, .080f, .97f)
    }
    AmberGlassRole.Panel -> if (dark) {
        GlassRecipe(GlassShadow(28f, 10f, .40f), GlassShadow(2f, 1f, .24f), .14f, 0f, .10f, 1f)
    } else {
        GlassRecipe(GlassShadow(28f, 10f, .09f), GlassShadow(2f, 1f, .04f), .95f, 0f, .07f, 1f)
    }
}

/** Top and bottom of the curved body. The tone change stays inside one warm neutral. */
private fun glassFill(role: AmberGlassRole, tokens: AmberTokens): Pair<Color, Color> {
    val bg = tokens.bg
    val ink = tokens.ink
    return if (tokens.isDark) {
        val base = tokens.surface2
        when (role) {
            AmberGlassRole.Control, AmberGlassRole.Chip ->
                lerp(base, Color.White, .09f) to lerp(base, bg, .08f)
            AmberGlassRole.Well -> lerp(bg, base, .55f) to lerp(bg, base, .74f)
            AmberGlassRole.Lens -> lerp(base, ink, .20f) to lerp(base, ink, .13f)
            AmberGlassRole.Panel -> lerp(base, Color.White, .03f) to base
        }
    } else {
        when (role) {
            // Crisp warm-white glass controls that clearly separate from the paper canvas without feeling chunky.
            AmberGlassRole.Control ->
                lerp(bg, Color.White, .86f) to lerp(bg, Color.White, .72f)
            AmberGlassRole.Chip ->
                lerp(bg, Color.White, .84f) to lerp(bg, Color.White, .68f)
            // Quietly recessed track well.
            AmberGlassRole.Well -> lerp(bg, ink, .055f) to lerp(bg, ink, .032f)
            // Brightest pill inside the recessed well.
            AmberGlassRole.Lens -> lerp(bg, Color.White, .95f) to lerp(bg, Color.White, .84f)
            AmberGlassRole.Panel -> lerp(bg, Color.White, .70f) to lerp(bg, Color.White, .35f)
        }
    }
}

/**
 * Android stand-in for iOS Liquid Glass on Amber's floating chrome.
 *
 * Depth is built from soft parts instead of a platform elevation shadow (which reads as a grey
 * step under light controls): a small ambient shadow plus a tight contact shadow, a translucent
 * body that brightens toward the top of the curve, a specular arc that fades out along the curve,
 * light on the lower lip and a hairline that only seats the lower half. Clips to [shape].
 *
 * @param highlighted tints the surface toward the accent (open panel, active tool).
 * @param fill replaces the neutral body, e.g. the accent send button. The specular arc stays.
 * @param presence 0..1 fades the whole surface, used when a [AmberGlassRole.Lens] moves away.
 */
@Composable
fun Modifier.amberGlass(
    shape: Shape,
    role: AmberGlassRole = AmberGlassRole.Control,
    highlighted: Boolean = false,
    fill: Color? = null,
    presence: Float = 1f,
): Modifier {
    val tokens = LocalAmberTokens.current
    val r = recipe(role, tokens.isDark)
    val (fillTop, fillBottom) = fill?.let { lerp(it, Color.White, if (tokens.isDark) .06f else .12f) to it }
        ?: glassFill(role, tokens)
    val bodyAlpha = if (fill != null) 1f else r.bodyAlpha
    val accentMix by animateFloatAsState(
        targetValue = if (highlighted) 1f else 0f,
        animationSpec = tween(180),
        label = "amber-glass-highlight",
    )
    val p = presence.coerceIn(0f, 1f)
    val shadowInk = if (tokens.isDark) Color.Black else lerp(tokens.ink, Color.Black, .35f)

    var m: Modifier = this
    if (p > 0f) {
        r.ambient?.let { s ->
            m = m.dropShadow(
                shape,
                Shadow(radius = s.radius.dp, color = shadowInk.copy(alpha = s.alpha * p), offset = DpOffset(0.dp, s.y.dp)),
            )
        }
        r.contact?.let { s ->
            m = m.dropShadow(
                shape,
                Shadow(radius = s.radius.dp, color = shadowInk.copy(alpha = s.alpha * p), offset = DpOffset(0.dp, s.y.dp)),
            )
        }
    }
    return m
        .clip(shape)
        .drawWithCache {
            val hair = max(1, (.5f * density).roundToInt()).toFloat()
            val rimWidth = if (role == AmberGlassRole.Panel) max(hair * 1.5f, 1.dp.toPx()) else hair
            fun tinted(c: Color): Color =
                if (accentMix <= 0f || fill != null) c
                else lerp(c, tokens.accent, (if (tokens.isDark) .16f else .10f) * accentMix)
            val top = tinted(fillTop).let { it.copy(alpha = it.alpha * bodyAlpha * p) }
            val bottom = tinted(fillBottom).let { it.copy(alpha = it.alpha * bodyAlpha * p) }
            val body = Brush.verticalGradient(listOf(top, bottom), startY = 0f, endY = size.height)
            val bodyOutline = shape.createOutline(size, layoutDirection, this)

            // Specular light lives on the upper arc and is gone by the time the curve turns away.
            val h = size.height
            val fade = min(h * .50f, 26.dp.toPx())
            val lip = min(h * .28f, 12.dp.toPx())
            val white = Color.White
            val rimBrush = Brush.verticalGradient(
                0f to white.copy(alpha = r.rimTop * p),
                (fade * .40f / h) to white.copy(alpha = r.rimTop * .35f * p),
                (fade / h) to Color.Transparent,
                ((h - lip) / h).coerceAtLeast(fade / h) to Color.Transparent,
                1f to white.copy(alpha = r.rimBottom * p),
                startY = 0f,
                endY = h,
            )
            val inset = hair + rimWidth / 2f
            val rimOutline = shape.createOutline(
                Size(size.width - inset * 2f, size.height - inset * 2f),
                layoutDirection,
                this,
            )
            val edgeBase = when {
                tokens.isDark -> white
                fill != null -> Color.Black
                else -> tokens.ink
            }
            val edgeStrong = lerp(
                edgeBase.copy(alpha = r.edge),
                tokens.accent.copy(alpha = if (tokens.isDark) .45f else .30f),
                if (fill == null) accentMix else 0f,
            ).let { it.copy(alpha = it.alpha * p) }
            // Light from above: the top edge carries a subtle hairline that deepens toward the base.
            val topEdgeFactor = when {
                accentMix > 0f -> .60f
                role == AmberGlassRole.Panel -> .15f
                else -> .35f
            }
            val edgeBrush = Brush.verticalGradient(
                0f to edgeStrong.copy(alpha = edgeStrong.alpha * topEdgeFactor),
                .55f to edgeStrong.copy(alpha = edgeStrong.alpha * .75f),
                1f to edgeStrong,
                startY = 0f,
                endY = h,
            )
            val edgeOutline = shape.createOutline(
                Size(size.width - hair, size.height - hair),
                layoutDirection,
                this,
            )
            onDrawBehind {
                drawOutline(bodyOutline, brush = body)
                translate(inset, inset) {
                    drawOutline(rimOutline, brush = rimBrush, style = Stroke(rimWidth))
                }
                translate(hair / 2f, hair / 2f) {
                    drawOutline(edgeOutline, brush = edgeBrush, style = Stroke(hair))
                }
            }
        }
}
