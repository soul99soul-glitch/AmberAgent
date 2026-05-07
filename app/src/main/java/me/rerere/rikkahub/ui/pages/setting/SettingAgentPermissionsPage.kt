package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Alert01
import me.rerere.hugeicons.stroke.Settings03
import me.rerere.hugeicons.stroke.Zap
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.PulseDialogButton
import me.rerere.rikkahub.ui.components.ui.PulseDialogVariant
import me.rerere.rikkahub.ui.components.ui.Switch
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel

@Composable
fun SettingAgentPermissionsPage(vm: SettingVM = koinViewModel()) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val navController = LocalNavController.current
    val settings by vm.settings.collectAsStateWithLifecycle()
    var showHighRiskAutoApproveDialog by remember { mutableStateOf(false) }

    val autoApproveAll = settings.agentRuntime.autoApproveAllToolCalls
    val autoApproveHighRisk = settings.agentRuntime.autoApproveHighRiskToolCalls

    if (showHighRiskAutoApproveDialog) {
        AlertDialog(
            onDismissRequest = { showHighRiskAutoApproveDialog = false },
            icon = { Icon(HugeIcons.Alert01, null) },
            title = { Text(stringResource(R.string.setting_page_agent_high_risk_auto_approve_confirm_title)) },
            text = { Text(stringResource(R.string.setting_page_agent_high_risk_auto_approve_confirm_desc)) },
            confirmButton = {
                PulseDialogButton(
                    onClick = {
                        showHighRiskAutoApproveDialog = false
                        vm.updateSettings(
                            settings.copy(
                                agentRuntime = settings.agentRuntime.copy(
                                    autoApproveHighRiskToolCalls = true
                                )
                            )
                        )
                    },
                    text = stringResource(R.string.confirm),
                    variant = PulseDialogVariant.Secondary,
                )
            },
            dismissButton = {
                PulseDialogButton(
                    onClick = { showHighRiskAutoApproveDialog = false },
                    text = stringResource(R.string.cancel),
                    variant = PulseDialogVariant.Ghost,
                )
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.setting_agent_permissions_page_title)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding + PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                TrustLevelHero(
                    autoApproveAll = autoApproveAll,
                    autoApproveHighRisk = autoApproveHighRisk,
                )
            }

            item {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text(stringResource(R.string.setting_agent_permissions_access_section)) },
                ) {
                    item(
                        onClick = { navController.navigate(Screen.SettingSystemAccess) },
                        leadingContent = { Icon(HugeIcons.Settings03, null) },
                        supportingContent = { Text(stringResource(R.string.setting_page_system_access_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_page_system_access)) },
                    )
                }
            }

            item {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text(stringResource(R.string.setting_agent_permissions_approval_section)) },
                ) {
                    item(
                        leadingContent = { Icon(HugeIcons.Zap, null) },
                        supportingContent = { Text(stringResource(R.string.setting_page_agent_auto_approve_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_page_agent_auto_approve)) },
                        trailingContent = {
                            Switch(
                                checked = autoApproveAll,
                                onCheckedChange = { checked ->
                                    vm.updateSettings(
                                        settings.copy(
                                            agentRuntime = settings.agentRuntime.copy(
                                                autoApproveAllToolCalls = checked
                                            )
                                        )
                                    )
                                }
                            )
                        },
                    )
                    item(
                        leadingContent = { Icon(HugeIcons.Alert01, null) },
                        supportingContent = { Text(stringResource(R.string.setting_page_agent_high_risk_auto_approve_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_page_agent_high_risk_auto_approve)) },
                        trailingContent = {
                            Switch(
                                checked = autoApproveHighRisk,
                                onCheckedChange = { checked ->
                                    if (checked) {
                                        showHighRiskAutoApproveDialog = true
                                    } else {
                                        vm.updateSettings(
                                            settings.copy(
                                                agentRuntime = settings.agentRuntime.copy(
                                                    autoApproveHighRiskToolCalls = false
                                                )
                                            )
                                        )
                                    }
                                }
                            )
                        },
                    )
                }
            }
        }
    }
}

/**
 * Ink-grounded permissions hero. Renders the agent's "trust level" as
 * a 5-dot streak — lit dots represent auto-approval grants the user
 * has flipped on, unlit dots are still gated. The fraction at the
 * right shows the current state (e.g. "0/5 GATED", "5/5 OPEN") in
 * sport-orange to make it pop on the dark canvas.
 *
 * The five buckets aren't all real toggles in the current settings
 * model, so the hero uses a virtual scale: each unlocked bucket
 * collapses two state bits into one dot. This gives the visual
 * "Pulse trust meter" feel without requiring new state plumbing —
 * if the user flips the existing two toggles to ON, the hero reads
 * 5/5; if both OFF, it reads 0/5; mid-state shows partial fill.
 */
@Composable
private fun TrustLevelHero(
    autoApproveAll: Boolean,
    autoApproveHighRisk: Boolean,
    modifier: Modifier = Modifier,
) {
    // Virtual 5-dot scale: 0 toggles = 0 dots, all-only = 3 dots,
    // all + high-risk = 5 dots. Mirrors a "more open" → "more dots
    // lit" reading even though we only have two underlying booleans.
    val lit = when {
        autoApproveAll && autoApproveHighRisk -> 5
        autoApproveAll -> 3
        else -> 0
    }
    val totalDots = 5

    val statusLabel = when (lit) {
        0 -> stringResource(R.string.setting_agent_permissions_trust_label_gated)
        in 1..3 -> stringResource(R.string.setting_agent_permissions_trust_label_partial)
        else -> stringResource(R.string.setting_agent_permissions_trust_label_open)
    }
    val statusColor = when (lit) {
        0 -> MaterialTheme.colorScheme.primary       // chartreuse — "max security"
        in 1..3 -> MaterialTheme.colorScheme.onTertiary // cream — neutral
        else -> MaterialTheme.colorScheme.secondary  // sport-orange — "wide open"
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.tertiary,
        contentColor = MaterialTheme.colorScheme.onTertiary,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = stringResource(R.string.setting_agent_permissions_trust_eyebrow).uppercase(),
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.1.em,
                        ),
                        color = MaterialTheme.colorScheme.onTertiary.copy(alpha = 0.65f),
                    )
                    Row(
                        verticalAlignment = Alignment.Bottom,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = "$lit/$totalDots",
                            fontSize = 36.sp,
                            fontWeight = FontWeight.Black,
                            color = statusColor,
                        )
                        Text(
                            text = statusLabel.uppercase(),
                            modifier = Modifier.padding(bottom = 8.dp),
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.08.em,
                            ),
                            color = statusColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            // Streak grid — 5 dots horizontally. Lit = chartreuse,
            // unlit = ink2 (a slightly lighter dark for visibility on
            // the ink ground).
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                repeat(totalDots) { index ->
                    StreakDot(
                        modifier = Modifier.weight(1f),
                        lit = index < lit,
                    )
                }
            }
            Text(
                text = stringResource(R.string.setting_agent_permissions_trust_caption),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiary.copy(alpha = 0.65f),
            )
        }
    }
}

@Composable
private fun StreakDot(
    lit: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .height(8.dp)
            .clip(CircleShape)
            .background(
                if (lit) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onTertiary.copy(alpha = 0.12f)
            ),
    )
}
