package app.amber.feature.ui.pages.personalagent

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import app.amber.feature.ui.components.ds.AmberGlassRole
import app.amber.feature.ui.components.ds.amberGlass
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.amber.agent.R
import app.amber.feature.macgateway.GatewayEndpointRole
import app.amber.feature.macgateway.MacGatewayRepository
import app.amber.feature.ui.theme.LocalAmberTokens
import kotlin.math.abs

enum class PersonalAgentModeTab {
    CHAT,
    TASKS,
    CENTER,
}

/**
 * Resolves the target tab when tapping a segment in the 3-tab mode switcher:
 * tapping the active Tasks/Center tab toggles back to Chat (aligned with iOS `AmberDuetBottomSwitcher`),
 * while tapping Chat always returns to Chat.
 */
internal fun nextModeTabOnTap(
    current: PersonalAgentModeTab,
    tapped: PersonalAgentModeTab,
): PersonalAgentModeTab = when {
    tapped == PersonalAgentModeTab.CHAT -> PersonalAgentModeTab.CHAT
    current == tapped -> PersonalAgentModeTab.CHAT
    else -> tapped
}

/**
 * Resolves the hovered tab during horizontal scrubbing along the 3-segment track.
 */
internal fun resolveScrubbedModeTab(
    x: Float,
    tabMidXs: Map<PersonalAgentModeTab, Pair<Float, Float>>,
): PersonalAgentModeTab? {
    if (tabMidXs.isEmpty()) return null
    tabMidXs.entries.firstOrNull { (_, range) -> x >= range.first && x <= range.second }?.let { return it.key }
    return tabMidXs.entries.minByOrNull { (_, range) ->
        val mid = (range.first + range.second) * 0.5f
        abs(mid - x)
    }?.key
}

/**
 * Iconic Amber Duet `3+1` bottom mode switcher bar:
 * - Left: 3-segment capsule track (`对话 | 任务 [badge] | 中心`) with distinct recessed track,
 *   raised active pill with theme-aware contrast, and horizontal scrub switching
 * - Right: `+1` host switcher capsule (`[icon] 本机 •` / `[icon] 云端 •`)
 */
@Composable
fun PersonalAgentBottomBar(
    selectedTab: PersonalAgentModeTab,
    activeTaskCount: Int,
    selectedRole: GatewayEndpointRole?,
    cloud: MacGatewayRepository.State,
    mac: MacGatewayRepository.State,
    onSelectChat: () -> Unit,
    onOpenTasks: () -> Unit,
    onOpenCenter: () -> Unit,
    onSelectHost: (GatewayEndpointRole?) -> Unit,
    onOpenGatewayPairing: () -> Unit,
    modifier: Modifier = Modifier,
    onScrubTab: ((PersonalAgentModeTab) -> Unit)? = null,
) {
    val tokens = LocalAmberTokens.current
    val tabBounds = remember { mutableStateMapOf<PersonalAgentModeTab, Pair<Float, Float>>() }
    val latestSelectedTab by rememberUpdatedState(selectedTab)
    val latestScrubHandler by rememberUpdatedState(onScrubTab)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .testTag("personal-agent-bottom-bar")
            .padding(top = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Left: 3-segment track well with tap + horizontal scrub
        Row(
            modifier = Modifier
                .height(40.dp)
                .amberGlass(CircleShape, AmberGlassRole.Well)
                .pointerInput(Unit) {
                    detectHorizontalDragGestures { change, _ ->
                        val target = resolveScrubbedModeTab(change.position.x, tabBounds) ?: return@detectHorizontalDragGestures
                        if (target != latestSelectedTab) {
                            val handler = latestScrubHandler
                            if (handler != null) {
                                handler(target)
                            } else {
                                when (target) {
                                    PersonalAgentModeTab.CHAT -> onSelectChat()
                                    PersonalAgentModeTab.TASKS -> onOpenTasks()
                                    PersonalAgentModeTab.CENTER -> onOpenCenter()
                                }
                            }
                        }
                    }
                }
                .padding(4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            ModeSegmentButton(
                label = stringResource(R.string.personal_agent_tab_chat),
                selected = selectedTab == PersonalAgentModeTab.CHAT,
                badgeCount = 0,
                onClick = onSelectChat,
                modifier = Modifier
                    .testTag("personal-agent-tab-chat")
                    .onGloballyPositioned { coords ->
                        val rect = coords.boundsInParent()
                        tabBounds[PersonalAgentModeTab.CHAT] = rect.left to rect.right
                    },
            )
            ModeSegmentButton(
                label = stringResource(R.string.personal_agent_tab_tasks),
                selected = selectedTab == PersonalAgentModeTab.TASKS,
                badgeCount = activeTaskCount,
                onClick = onOpenTasks,
                modifier = Modifier
                    .testTag("personal-agent-tab-tasks")
                    .onGloballyPositioned { coords ->
                        val rect = coords.boundsInParent()
                        tabBounds[PersonalAgentModeTab.TASKS] = rect.left to rect.right
                    },
            )
            ModeSegmentButton(
                label = stringResource(R.string.personal_agent_tab_center),
                selected = selectedTab == PersonalAgentModeTab.CENTER,
                badgeCount = 0,
                onClick = onOpenCenter,
                modifier = Modifier
                    .testTag("personal-agent-tab-center")
                    .onGloballyPositioned { coords ->
                        val rect = coords.boundsInParent()
                        tabBounds[PersonalAgentModeTab.CENTER] = rect.left to rect.right
                    },
            )
        }

        Spacer(Modifier.weight(1f))

        // Right: +1 host switcher pill
        PersonalAgentHostPill(
            selectedRole = selectedRole,
            cloud = cloud,
            mac = mac,
            onSelectHost = onSelectHost,
            onOpenGatewayPairing = onOpenGatewayPairing,
        )
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
    // The selected tab is a raised glass lens resting in the well (iOS `DuetActiveModeLensModifier`):
    // the surface step and the ink/ink3 label contrast carry the selection, not an accent ring.
    val lensPresence by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = tween(durationMillis = 180),
        label = "modeTabLens",
    )
    // Use `tokens.ink3` (muted) for unselected tabs rather than `tokens.ink2` because portable
    // theme palettes map `ink2 == ink`, which previously flattened selected vs unselected copy.
    val targetTextColor = if (selected) tokens.ink else tokens.ink3

    val animatedTextColor by animateColorAsState(
        targetValue = targetTextColor,
        animationSpec = tween(durationMillis = 160),
        label = "modeTabText",
    )

    Row(
        modifier = modifier
            .height(32.dp)
            .amberGlass(CircleShape, AmberGlassRole.Lens, presence = lensPresence)
            .semantics { this.selected = selected }
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .padding(horizontal = if (badgeCount > 0) 12.dp else 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Text(
            text = label,
            color = animatedTextColor,
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
