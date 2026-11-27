package app.amber.feature.ui.pages.personalagent

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.amber.agent.R
import app.amber.feature.ui.theme.LocalAmberTokens
import com.composables.icons.lucide.Inbox
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Menu

internal val PersonalAgentTopChromeHeight = 104.dp

/**
 * Floating 3-piece Personal Agent top bar aligned with iOS & HarmonyOS Amber Duet:
 * - Soft vertical gradient fade from `bg` to transparent (zero hard divider line)
 * - Top-left 44dp circular raised Menu (`≡`) button opening the Workspace bottom sheet
 * - Centered 64dp Persona Medallion + overlapping bottom topic pill (tapping pill opens model menu)
 * - Top-right 44dp circular raised Inbox button with optional terracotta badge count
 */
@Composable
fun PersonalAgentTopChrome(
    topicLabel: String,
    generating: Boolean,
    recapEligible: Boolean,
    proactiveBadgeCount: Int,
    onOpenWorkspace: () -> Unit,
    onOpenAgentSoul: () -> Unit,
    onToggleModelMenu: () -> Unit,
    onOpenRecap: () -> Unit,
    onOpenProactiveCenter: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalAmberTokens.current
    val menuA11y = stringResource(R.string.personal_agent_top_menu_a11y)
    val inboxA11y = stringResource(R.string.personal_agent_top_inbox_a11y)
    val personaA11y = stringResource(R.string.personal_agent_top_persona_a11y)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    0.0f to tokens.bg,
                    0.56f to tokens.bg.copy(alpha = 0.94f),
                    0.82f to tokens.bg.copy(alpha = 0.56f),
                    1.0f to Color.Transparent,
                ),
            )
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .height(PersonalAgentTopChromeHeight - 12.dp),
    ) {
        // Left: 44dp raised circular Menu button
        FloatingTopCircleButton(
            onClick = onOpenWorkspace,
            contentDescription = menuA11y,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(top = 6.dp)
                .testTag("personal-agent-more"),
        ) {
            Icon(
                imageVector = Lucide.Menu,
                contentDescription = null,
                tint = tokens.ink,
                modifier = Modifier.size(20.dp),
            )
        }

        // Center: 64dp Persona Medallion + overlapping topic pill (-10dp overlap)
        Box(
            modifier = Modifier.align(Alignment.TopCenter),
            contentAlignment = Alignment.TopCenter,
        ) {
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .testTag("chat-header-agent-soul")
                    .combinedClickable(
                        role = Role.Button,
                        onClick = onOpenAgentSoul,
                    )
                    .semantics { contentDescription = personaA11y },
                contentAlignment = Alignment.Center,
            ) {
                AmberPersonaMedallion(
                    diameter = 64.dp,
                    generating = generating,
                )
            }

            TopicCapsulePill(
                label = topicLabel,
                generating = generating,
                onClick = onToggleModelMenu,
                onLongClick = if (recapEligible) onOpenRecap else null,
                modifier = Modifier
                    .padding(top = 54.dp)
                    .testTag("chat-header-model-menu"),
            )
        }

        // Right: 44dp raised circular Inbox button with optional terracotta badge
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 6.dp),
        ) {
            FloatingTopCircleButton(
                onClick = onOpenProactiveCenter,
                contentDescription = inboxA11y,
                modifier = Modifier.testTag("personal-agent-proactive-inbox"),
            ) {
                Icon(
                    imageVector = Lucide.Inbox,
                    contentDescription = null,
                    tint = tokens.ink,
                    modifier = Modifier.size(20.dp),
                )
            }

            if (proactiveBadgeCount > 0) {
                val badgeText = if (proactiveBadgeCount > 99) "99+" else proactiveBadgeCount.toString()
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 3.dp, y = (-3).dp)
                        .heightIn(min = 18.dp)
                        .widthIn(min = 18.dp)
                        .clip(CircleShape)
                        .background(tokens.accent)
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = badgeText,
                        color = tokens.accentInk,
                        fontSize = 10.sp,
                        lineHeight = 12.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun FloatingTopCircleButton(
    onClick: () -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val tokens = LocalAmberTokens.current
    val elevation = if (tokens.isDark) 0.dp else 3.dp
    Surface(
        onClick = onClick,
        modifier = modifier
            .size(44.dp)
            .shadow(
                elevation = elevation,
                shape = CircleShape,
                clip = false,
                ambientColor = Color.Black.copy(alpha = 0.10f),
                spotColor = Color.Black.copy(alpha = 0.08f),
            )
            .semantics { this.contentDescription = contentDescription },
        shape = CircleShape,
        color = tokens.raised,
        contentColor = tokens.ink,
        border = BorderStroke(1.dp, tokens.line),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Box(contentAlignment = Alignment.Center) {
            content()
        }
    }
}

@Composable
private fun TopicCapsulePill(
    label: String,
    generating: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalAmberTokens.current
    val elevation = if (tokens.isDark) 0.dp else 3.dp
    val pulse = if (generating) {
        val transition = rememberInfiniteTransition(label = "topicPillPulse")
        transition.animateFloat(
            initialValue = 0.35f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 850, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "topicPillDotAlpha",
        ).value
    } else {
        1f
    }

    Surface(
        modifier = modifier
            .heightIn(min = 28.dp)
            .widthIn(max = 184.dp)
            .shadow(
                elevation = elevation,
                shape = CircleShape,
                clip = false,
                ambientColor = Color.Black.copy(alpha = 0.10f),
                spotColor = Color.Black.copy(alpha = 0.08f),
            )
            .clip(CircleShape)
            .combinedClickable(
                role = Role.Button,
                onClick = onClick,
                onLongClick = onLongClick,
            ),
        shape = CircleShape,
        color = tokens.raised,
        contentColor = tokens.ink,
        border = BorderStroke(1.dp, tokens.line),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 13.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (generating) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .graphicsLayer { alpha = pulse }
                        .clip(CircleShape)
                        .background(tokens.accent),
                )
            }
            Text(
                text = label,
                color = tokens.ink,
                fontSize = 12.5.sp,
                lineHeight = 16.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
