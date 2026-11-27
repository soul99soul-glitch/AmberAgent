package app.amber.feature.ui.pages.personalagent

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import app.amber.feature.ui.components.ds.AmberGlassRole
import app.amber.feature.ui.components.ds.amberGlass
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
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
import app.amber.feature.macgateway.GatewayEndpointRole
import app.amber.feature.macgateway.MacGatewayRepository
import app.amber.feature.ui.pages.setting.labelRes
import app.amber.feature.ui.theme.LocalAmberTokens
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.Cloud
import com.composables.icons.lucide.Link2
import com.composables.icons.lucide.Laptop
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Smartphone

/**
 * Bottom-right `+1` host switcher pill aligned with iOS & HarmonyOS Amber Duet:
 * - Compact 40dp capsule showing `[phone/cloud icon] 本机 •` / `[cloud icon] 云端 •` (no chevron)
 * Tap toggles the only paired host or opens the menu when both are available.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PersonalAgentHostPill(
    selectedRole: GatewayEndpointRole?,
    cloud: MacGatewayRepository.State,
    mac: MacGatewayRepository.State,
    onSelectHost: (GatewayEndpointRole?) -> Unit,
    onOpenGatewayPairing: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val tokens = LocalAmberTokens.current
    var expanded by remember { mutableStateOf(false) }

    val localLabel = stringResource(R.string.personal_agent_host_local)
    val activeLabel = selectedRole?.let { stringResource(it.labelRes) } ?: localLabel
    val remoteSelected = selectedRole != null
    val switchA11y = stringResource(R.string.personal_agent_host_switch_a11y, activeLabel)

    val dotColor = if (remoteSelected) {
        tokens.accent
    } else {
        tokens.ink3
    }

    Box(modifier = modifier) {
        Row(
            modifier = Modifier
                .height(40.dp)
                .amberGlass(CircleShape, AmberGlassRole.Control, highlighted = remoteSelected)
                .testTag("personal-agent-host-pill")
                .combinedClickable(
                    role = Role.Button,
                    onClick = {
                        when {
                            cloud.canSelectCloud && mac.canSelectCloud -> expanded = true
                            remoteSelected -> onSelectHost(null)
                            cloud.canSelectCloud -> onSelectHost(GatewayEndpointRole.CLOUD)
                            mac.canSelectCloud -> onSelectHost(GatewayEndpointRole.MAC)
                            else -> onOpenGatewayPairing()
                        }
                    },
                    onLongClick = { expanded = true },
                )
                .semantics { contentDescription = switchA11y }
                .padding(horizontal = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = when (selectedRole) {
                    GatewayEndpointRole.CLOUD -> Lucide.Cloud
                    GatewayEndpointRole.MAC -> Lucide.Laptop
                    null -> Lucide.Smartphone
                },
                contentDescription = null,
                tint = if (remoteSelected) tokens.accent else tokens.ink2,
                modifier = Modifier.size(15.dp),
            )
            Text(
                text = activeLabel,
                color = if (remoteSelected) tokens.ink else tokens.ink2,
                fontSize = 13.sp,
                lineHeight = 16.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(dotColor),
            )
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            DropdownMenuItem(
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("personal-agent-host-option-local"),
                leadingIcon = {
                    Icon(
                        imageVector = Lucide.Smartphone,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                },
                text = {
                    Column {
                        Text(
                            text = localLabel,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = stringResource(R.string.personal_agent_host_local_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = tokens.ink3,
                        )
                    }
                },
                trailingIcon = {
                    if (!remoteSelected) {
                        Icon(
                            imageVector = Lucide.Check,
                            contentDescription = null,
                            tint = tokens.accent,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                },
                onClick = {
                    expanded = false
                    if (remoteSelected) {
                        onSelectHost(null)
                    }
                },
            )
            GatewayEndpointRole.entries.forEach { role ->
                val state = if (role == GatewayEndpointRole.CLOUD) cloud else mac
                val isSelected = role == selectedRole
                val label = stringResource(role.labelRes)
                DropdownMenuItem(
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("personal-agent-host-option-${role.name.lowercase()}"),
                    enabled = state.canSelectCloud,
                    leadingIcon = {
                        Icon(
                            imageVector = if (role == GatewayEndpointRole.CLOUD) Lucide.Cloud else Lucide.Laptop,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    },
                    text = {
                        Column {
                            Text(text = label, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                text = when {
                                    state.connection == null -> stringResource(R.string.amber_multi_device_not_paired)
                                    !state.canSelectCloud -> stringResource(R.string.amber_multi_device_dialogue_unavailable)
                                    else -> state.connection.name
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = tokens.ink3,
                            )
                        }
                    },
                    trailingIcon = {
                        if (isSelected) {
                            Icon(
                                imageVector = Lucide.Check,
                                contentDescription = null,
                                tint = tokens.accent,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    },
                    onClick = {
                        expanded = false
                        if (!isSelected) onSelectHost(role)
                    },
                )
            }
            DropdownMenuItem(
                modifier = Modifier.heightIn(min = 48.dp),
                leadingIcon = {
                    Icon(
                        imageVector = Lucide.Link2,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                },
                text = {
                    Text(
                        text = stringResource(R.string.setting_home_multi_device_title),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                },
                onClick = {
                    expanded = false
                    onOpenGatewayPairing()
                },
            )
        }
    }
}
