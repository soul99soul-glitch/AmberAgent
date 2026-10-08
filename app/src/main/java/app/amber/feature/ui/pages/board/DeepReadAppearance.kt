package app.amber.feature.ui.pages.board

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.amber.agent.R
import app.amber.feature.board.DeepReadAccentIds
import app.amber.feature.ui.components.ds.pressable
import app.amber.feature.ui.theme.LocalAmberTokens
import app.amber.feature.ui.theme.LocalAmberType
import app.amber.feature.ui.theme.accentInkFor
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.Lucide

// ─────────────────────────────────────────────────────────────────────────────
// Standalone DeepRead accent — ported from iOS DeepReadAppearance.swift.
// Five paper-tuned swatches (cinnabar/indigo/pine/wisteria/ink), each with a
// light/dark pair; the choice applies app-wide in the deepread product.
// ─────────────────────────────────────────────────────────────────────────────

enum class DeepReadAccentChoice(
    val id: String,
    val light: Color,
    val dark: Color,
    val nameRes: Int,
) {
    CINNABAR(DeepReadAccentIds.CINNABAR, Color(0xFFC8402F), Color(0xFFE67261), R.string.deepread_accent_cinnabar),
    INDIGO(DeepReadAccentIds.INDIGO, Color(0xFF2F5D8A), Color(0xFF7FA8D6), R.string.deepread_accent_indigo),
    PINE(DeepReadAccentIds.PINE, Color(0xFF2F6B57), Color(0xFF7DBFA4), R.string.deepread_accent_pine),
    WISTERIA(DeepReadAccentIds.WISTERIA, Color(0xFF6B4C9A), Color(0xFFB39DDB), R.string.deepread_accent_wisteria),
    INK(DeepReadAccentIds.INK, Color(0xFF2A2320), Color(0xFFF1E9DF), R.string.deepread_accent_ink),
    ;

    fun color(dark: Boolean): Color = if (dark) this.dark else this.light

    companion object {
        fun fromId(id: String?): DeepReadAccentChoice =
            entries.firstOrNull { it.id == id } ?: CINNABAR

        fun colorFor(id: String?, dark: Boolean): Color = fromId(id).color(dark)
    }
}

/** Row of five swatches; the ring glides to the chosen color — iOS AccentPicker parity. */
@Composable
fun DeepReadAccentPicker(
    selectedId: String,
    onSelect: (DeepReadAccentChoice) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalAmberTokens.current
    val haptics = LocalHapticFeedback.current
    val choices = DeepReadAccentChoice.entries
    val selectedIndex = choices.indexOfFirst { it == DeepReadAccentChoice.fromId(selectedId) }

    // iOS matchedGeometryEffect: one shared ring that glides between swatches.
    // Item centers are measured in parent coordinates; the overlay ring tracks
    // the selected one with a spring. The first measurement snaps into place —
    // gliding in from x=0 would read as a glitch.
    val centers = remember { mutableStateMapOf<Int, Float>() }
    val selectedSwatch = choices.getOrNull(selectedIndex)?.color(tokens.isDark) ?: tokens.accent
    val targetX = centers[selectedIndex]
    val ringX = remember { androidx.compose.animation.core.Animatable(0f) }
    var ringPlaced by remember { androidx.compose.runtime.mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(targetX) {
        val target = targetX ?: return@LaunchedEffect
        if (!ringPlaced || !app.amber.feature.ui.pages.novel.animationsEnabled()) {
            ringX.snapTo(target)
            ringPlaced = true
        } else {
            ringX.animateTo(
                target,
                spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMediumLow),
            )
        }
    }
    val ringVisible = targetX != null

    Box(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            choices.forEachIndexed { index, choice ->
                val selected = index == selectedIndex
                val swatch = choice.color(tokens.isDark)
                val label = stringResource(choice.nameRes)
                val dotScale by animateFloatAsState(if (selected) 1f else 0.86f, label = "dotScale")
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .onGloballyPositioned { coords ->
                            val cx = coords.boundsInParent().center.x
                            if (centers[index] != cx) centers[index] = cx
                        }
                        .semantics {
                            role = Role.RadioButton
                            this.selected = selected
                            contentDescription = label
                        }
                        .pressable(
                            onClick = {
                                if (!selected) {
                                    onSelect(choice)
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                }
                            },
                            role = Role.RadioButton,
                        )
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(44.dp)) {
                        Box(
                            Modifier
                                .size(32.dp)
                                .scale(dotScale)
                                .clip(CircleShape)
                                .background(swatch),
                            contentAlignment = Alignment.Center,
                        ) {
                            androidx.compose.animation.AnimatedVisibility(
                                visible = selected,
                                enter = scaleIn(spring(Spring.DampingRatioMediumBouncy)) + fadeIn(),
                            ) {
                                Icon(
                                    Lucide.Check,
                                    contentDescription = null,
                                    tint = accentInkFor(swatch),
                                    modifier = Modifier.size(14.dp),
                                )
                            }
                        }
                    }
                    Text(
                        label,
                        style = LocalAmberType.current.meta.copy(
                            fontSize = 11.sp,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        ),
                        color = if (selected) tokens.ink else tokens.ink3,
                    )
                }
            }
        }
        if (ringVisible) {
            // 42dp ring centered on the 44dp swatch slot (row 10dp + column 4dp top pad).
            val ringSizePx = with(androidx.compose.ui.platform.LocalDensity.current) { 42.dp.toPx() }
            val ringTopPx = with(androidx.compose.ui.platform.LocalDensity.current) { 15.dp.toPx() }
            Box(
                Modifier
                    .absoluteOffset { IntOffset((ringX.value - ringSizePx / 2f).roundToInt(), ringTopPx.roundToInt()) }
                    .size(42.dp)
                    .border(2.dp, selectedSwatch, CircleShape),
            )
        }
    }
}
