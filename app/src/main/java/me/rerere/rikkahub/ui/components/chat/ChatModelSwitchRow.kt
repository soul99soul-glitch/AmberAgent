package me.rerere.rikkahub.ui.components.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import kotlin.uuid.Uuid
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderSetting

/**
 * Pulse chat-screen quick model switcher.
 *
 * A horizontal scrollable strip of pill chips showing every CHAT-capable
 * model from the user's enabled providers. Sits between the chat TopBar
 * and the timeline so the active model is visible at a glance and
 * switching costs one tap. Mirrors the "GPT-4O · PERF | CLAUDE | GEMINI"
 * row in the Pulse mockup's Chat Screen.
 *
 * Why visible always (not tucked behind a dropdown):
 *   - The mockup explicitly shows it always-on; the user has asked us to
 *     replicate the mockup faithfully.
 *   - Multi-model use is the core product value of AmberAgent. Hiding
 *     model choice behind a tap penalises the most distinctive workflow.
 *   - One-tap switching invites experimentation ("did Claude do better
 *     than GPT here?") without forcing the user back into the composer
 *     expand panel.
 *
 * Active chip = ink/tertiary fill + chartreuse text (matches the mockup's
 * black-bg-chartreuse-text pattern and our existing ToolStatus chip
 * vocabulary). Inactive = transparent on cream + ink text + outlineVariant
 * hairline. Both are uppercase + letter-spaced for the "tech-performance"
 * eyebrow feel.
 *
 * Returns nothing (renders Spacer-equivalent zero height) when fewer than
 * two CHAT-capable models are configured — a one-model setup makes the
 * strip pure noise.
 *
 * @param currentModelId Currently active chat model (highlighted).
 * @param providers Provider list from settings (only enabled providers'
 *   CHAT models are listed).
 * @param onSelectModel Callback invoked with the chosen model on tap.
 */
@Composable
fun ChatModelSwitchRow(
    currentModelId: Uuid?,
    providers: List<ProviderSetting>,
    onSelectModel: (Model) -> Unit,
    modifier: Modifier = Modifier,
) {
    val chatModels = remember(providers) {
        providers
            .filter { it.enabled }
            .flatMap { it.models }
            .filter { it.type == ModelType.CHAT }
    }
    if (chatModels.size < 2) return

    val listState: LazyListState = rememberLazyListState()
    // Auto-scroll the active chip into view when the model changes — the
    // user otherwise has to manually scroll to see "where am I now".
    LaunchedEffect(currentModelId, chatModels) {
        val activeIndex = chatModels.indexOfFirst { it.id == currentModelId }
        if (activeIndex >= 0) {
            // animateScrollToItem aligns the item to the start of the
            // viewport; in practice the chip lands near the left edge,
            // which is fine — left edge = "you are here".
            listState.animateScrollToItem(activeIndex)
        }
    }

    LazyRow(
        modifier = modifier.fillMaxWidth(),
        state = listState,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(items = chatModels, key = { it.id.toString() }) { model ->
            ModelChip(
                model = model,
                active = model.id == currentModelId,
                onClick = { onSelectModel(model) },
            )
        }
    }
}

@Composable
private fun ModelChip(
    model: Model,
    active: Boolean,
    onClick: () -> Unit,
) {
    val container = if (active) {
        MaterialTheme.colorScheme.tertiary
    } else {
        MaterialTheme.colorScheme.surface
    }
    val content = if (active) {
        // Active chip text uses primary (chartreuse) on tertiary (ink)
        // — the same chartreuse-on-ink pairing as the active bottom-nav
        // pill (Phase 13) and PulseActivityIndicator label, so all
        // "this is where you are" cues read as one family.
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    val border = if (active) {
        null
    } else {
        BorderStroke(1.5.dp, MaterialTheme.colorScheme.outlineVariant)
    }

    Surface(
        modifier = Modifier
            .clip(CircleShape)
            .clickable(onClick = onClick),
        shape = CircleShape,
        color = container,
        contentColor = content,
        border = border,
    ) {
        // Uppercase + tight letter-spacing reads as "metric label" rather
        // than "model menu item" — matches the mockup's chip treatment.
        // CJK characters are unaffected by uppercase(), so models with
        // localised names ("通义千问") render unchanged.
        Text(
            text = model.displayName.uppercase(),
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.05.em,
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
