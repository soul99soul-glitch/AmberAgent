package me.rerere.rikkahub.ui.components.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * Empty-state hero for a fresh conversation with no messages yet.
 *
 * Renders the Pulse "Hi." greeting per the design mockup: a sport-orange
 * hero phrase at ~64sp weight 800 over an ALL-CAPS warm-grey eyebrow,
 * with a short helper paragraph below. Designed to slot into the
 * empty-timeline branch of [ChatList] when [me.rerere.rikkahub.data.model.Conversation.messageNodes]
 * is empty — the existing [ChatSuggestionsRow] continues to handle the
 * suggestion-chip overlay so this composable stays focused on the
 * "ready to talk" anchor without owning the actionable affordances.
 *
 * Why a hero phrase and not just a centered logo: opening a fresh chat
 * is the moment the user is most uncertain about what to say. Borrowing
 * the Pulse mockup's voice ("Hi." / "Ready.") gives the screen a
 * conversational lead-in without prescribing a topic, while the
 * sport-orange numeral-style typography reinforces the design system's
 * "this is the AmberAgent app, not generic Compose" identity.
 *
 * @param greeting Single-word headline ("Hi." / "Ready." / "Train smarter.").
 *   Defaults to "Hi." which works for any locale where punctuation reads neutrally.
 * @param eyebrow Optional ALL-CAPS context strip above the headline. Pass null to omit.
 *   Typical usage: model name + status, e.g. "READY · GPT-4o".
 * @param subtext Optional one-line helper paragraph below the headline.
 */
@Composable
fun NewChatHero(
    modifier: Modifier = Modifier,
    greeting: String = "Hi.",
    eyebrow: String? = null,
    subtext: String? = null,
    suggestions: List<String> = emptyList(),
    onClickSuggestion: (String) -> Unit = {},
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (eyebrow != null) {
            // ALL-CAPS context strip — same eyebrow treatment we apply to
            // CardGroup section labels for visual consistency. Sport-orange
            // signals "this is the active context" without competing with
            // the headline below.
            Text(
                text = eyebrow,
                color = MaterialTheme.colorScheme.secondary,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.10.em,
                ),
            )
            Spacer(modifier = Modifier.height(4.dp))
        }
        // Hero phrase: sport-orange, 64sp, weight 800, tight letter-spacing.
        // Anchors the screen visually so the user has a clear focal point
        // before scanning to the composer at the bottom.
        Text(
            text = greeting,
            color = MaterialTheme.colorScheme.secondary,
            style = MaterialTheme.typography.displayLarge.copy(
                fontSize = 64.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = (-0.04).em,
                lineHeight = 64.sp,
            ),
            textAlign = TextAlign.Start,
        )
        if (subtext != null) {
            Text(
                text = subtext,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        // Suggestion cards: cream-soft surface, full-width, tap-to-fill.
        // Per the Pulse mockup's "New Conversation" screen, the empty
        // state shows three deliberate suggestion cards rather than the
        // active-chat horizontal chip strip. Cards give each suggestion
        // more room to breathe and read as conversational prompts rather
        // than terse labels, which fits the "I just opened a fresh chat,
        // what should I try" mindset.
        if (suggestions.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            suggestions.take(SUGGESTION_LIMIT).forEach { suggestion ->
                SuggestionCard(
                    text = suggestion,
                    onClick = { onClickSuggestion(suggestion) },
                )
            }
        }
    }
}

private const val SUGGESTION_LIMIT = 3

@Composable
private fun SuggestionCard(
    text: String,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Sport-orange leading "›" mark anchors each card. Avoiding
            // emoji deliberately — emoji would look stickerish against
            // the otherwise restrained Pulse palette, and choosing a
            // per-suggestion emoji would require taxonomy we don't have
            // for arbitrary user-generated suggestions.
            Text(
                text = "›",
                color = MaterialTheme.colorScheme.secondary,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.ExtraBold,
                ),
            )
            Text(
                text = text,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = FontWeight.Medium,
                ),
            )
        }
    }
}
