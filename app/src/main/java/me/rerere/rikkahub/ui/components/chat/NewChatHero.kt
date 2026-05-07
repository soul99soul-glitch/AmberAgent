package me.rerere.rikkahub.ui.components.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
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
    }
}
