package app.amber.feature.ui.pages.personalagent

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.amber.agent.R
import app.amber.feature.ui.theme.LocalAmberTokens

enum class PersonalAgentModeTab {
    CHAT,
    TASKS,
    CENTER,
}

/**
 * Iconic Amber Duet `3+1` bottom mode switcher bar:
 * - Left: 3-segment capsule track (`对话 | 任务 [badge] | 中心`) with pure white raised active pill
 * - Right: `+1` host switcher capsule (`[icon] 本机 •` / `[icon] 云端 •`)
 * Smoothly collapses while the software keyboard is visible.
 */
@Composable
fun PersonalAgentBottomBar(
    visible: Boolean,
    selectedTab: PersonalAgentModeTab,
    activeTaskCount: Int,
    isCloudSelected: Boolean,
    cloudAvailable: Boolean,
    gatewayName: String?,
    onSelectChat: () -> Unit,
    onOpenTasks: () -> Unit,
    onOpenCenter: () -> Unit,
    onSelectCloud: (Boolean) -> Unit,
    onOpenGatewayPairing: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + expandVertically(expandFrom = Alignment.Bottom),
        exit = fadeOut() + shrinkVertically(shrinkTowards = Alignment.Bottom),
        modifier = modifier,
    ) {
        val tokens = LocalAmberTokens.current
        val trackElevation = if (tokens.isDark) 0.dp else 2.dp

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Left: 3-segment track well
            Row(
                modifier = Modifier
                    .height(40.dp)
                    .shadow(
                        elevation = trackElevation,
                        shape = CircleShape,
                        clip = false,
                        ambientColor = Color.Black.copy(alpha = 0.08f),
                        spotColor = Color.Black.copy(alpha = 0.06f),
                    )
                    .clip(CircleShape)
                    .background(tokens.surface)
                    .border(width = 1.dp, color = tokens.line, shape = CircleShape)
                    .padding(3.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                ModeSegmentButton(
                    label = stringResource(R.string.personal_agent_tab_chat),
                    selected = selectedTab == PersonalAgentModeTab.CHAT,
                    badgeCount = 0,
                    onClick = onSelectChat,
                    modifier = Modifier.testTag("personal-agent-tab-chat"),
                )
                ModeSegmentButton(
                    label = stringResource(R.string.personal_agent_tab_tasks),
                    selected = selectedTab == PersonalAgentModeTab.TASKS,
                    badgeCount = activeTaskCount,
                    onClick = onOpenTasks,
                    modifier = Modifier.testTag("personal-agent-tab-tasks"),
                )
                ModeSegmentButton(
                    label = stringResource(R.string.personal_agent_tab_center),
                    selected = selectedTab == PersonalAgentModeTab.CENTER,
                    badgeCount = 0,
                    onClick = onOpenCenter,
                    modifier = Modifier.testTag("personal-agent-tab-center"),
                )
            }

            Spacer(Modifier.weight(1f))

            // Right: +1 host switcher pill
            PersonalAgentHostPill(
                isCloudSelected = isCloudSelected,
                cloudAvailable = cloudAvailable,
                gatewayName = gatewayName,
                onSelectCloud = onSelectCloud,
                onOpenGatewayPairing = onOpenGatewayPairing,
            )
        }
    }
}

@Composable
private fun ModeSegmentButton(
    label: String,
    selected: Boolean,
    badgeCount: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalAmberTokens.current
    val activeShadow = if (selected && !tokens.isDark) 2.dp else 0.dp

    Row(
        modifier = modifier
            .height(34.dp)
            .shadow(
                elevation = activeShadow,
                shape = CircleShape,
                clip = false,
                ambientColor = Color.Black.copy(alpha = 0.10f),
                spotColor = Color.Black.copy(alpha = 0.08f),
            )
            .clip(CircleShape)
            .background(if (selected) tokens.raised else Color.Transparent)
            .clickable(role = Role.Tab, onClick = onClick)
            .padding(horizontal = if (badgeCount > 0) 12.dp else 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Text(
            text = label,
            color = if (selected) tokens.ink else tokens.ink2,
            fontSize = 13.sp,
            lineHeight = 16.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 1,
        )
        if (badgeCount > 0) {
            Box(
                modifier = Modifier
                    .heightIn(min = 17.dp)
                    .widthIn(min = 17.dp)
                    .clip(CircleShape)
                    .background(tokens.accent)
                    .padding(horizontal = 5.dp, vertical = 1.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (badgeCount > 99) "99+" else badgeCount.toString(),
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
