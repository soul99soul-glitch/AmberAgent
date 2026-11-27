package app.amber.feature.ui.pages.personalagent

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
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
import app.amber.feature.ui.theme.LocalAmberTokens
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.Cloud
import com.composables.icons.lucide.Link2
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Smartphone

/**
 * Bottom-right `+1` host switcher pill aligned with iOS & HarmonyOS Amber Duet:
 * - Compact 40dp capsule showing `[phone/cloud icon] 本机 •` / `[cloud icon] 云端 •` (no chevron)
 * - Tap directly toggles Local <-> Cloud (or jumps to gateway pairing when unpaired)
 * - Long-press opens the explicit host selection menu
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PersonalAgentHostPill(
    isCloudSelected: Boolean,
    cloudAvailable: Boolean,
    gatewayName: String?,
    onSelectCloud: (Boolean) -> Unit,
    onOpenGatewayPairing: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val tokens = LocalAmberTokens.current
    var expanded by remember { mutableStateOf(false) }

    val localLabel = stringResource(R.string.personal_agent_host_local)
    val cloudLabel = stringResource(R.string.personal_agent_host_cloud)
    val activeLabel = if (isCloudSelected) cloudLabel else localLabel
    val switchA11y = stringResource(R.string.personal_agent_host_switch_a11y, activeLabel)

    val pillFill = if (isCloudSelected) {
        tokens.accent.copy(alpha = if (tokens.isDark) 0.18f else 0.10f).compositeOver(tokens.surface)
    } else {
        tokens.surface
    }
    val pillBorder = if (isCloudSelected) {
        tokens.accent.copy(alpha = 0.36f)
    } else {
        tokens.line
    }
    val dotColor = if (isCloudSelected) {
        tokens.accent
    } else {
        Color(0xFF4B8F7C)
    }
    val elevation = if (tokens.isDark) 0.dp else 2.dp

    Box(modifier = modifier) {
        Row(
            modifier = Modifier
                .height(40.dp)
                .shadow(
                    elevation = elevation,
                    shape = CircleShape,
                    clip = false,
                    ambientColor = Color.Black.copy(alpha = 0.08f),
                    spotColor = Color.Black.copy(alpha = 0.06f),
                )
                .clip(CircleShape)
                .background(pillFill)
                .border(width = 1.dp, color = pillBorder, shape = CircleShape)
                .testTag("personal-agent-host-pill")
                .combinedClickable(
                    role = Role.Button,
                    onClick = {
                        if (cloudAvailable) {
                            onSelectCloud(!isCloudSelected)
                        } else {
                            onOpenGatewayPairing()
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
                imageVector = if (isCloudSelected) Lucide.Cloud else Lucide.Smartphone,
                contentDescription = null,
                tint = if (isCloudSelected) tokens.accent else tokens.ink2,
                modifier = Modifier.size(15.dp),
            )
            Text(
                text = activeLabel,
                color = if (isCloudSelected) tokens.ink else tokens.ink2,
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
                    if (!isCloudSelected) {
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
                    if (isCloudSelected) {
                        onSelectCloud(false)
                    }
                },
            )
            DropdownMenuItem(
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("personal-agent-host-option-cloud"),
                enabled = cloudAvailable,
                leadingIcon = {
                    Icon(
                        imageVector = Lucide.Cloud,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                },
                text = {
                    Column {
                        Text(
                            text = cloudLabel,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = if (cloudAvailable) {
                                stringResource(
                                    R.string.personal_agent_host_cloud_desc_paired,
                                    gatewayName?.takeIf { it.isNotBlank() } ?: cloudLabel,
                                )
                            } else if (gatewayName != null) {
                                stringResource(R.string.personal_agent_host_cloud_unsupported)
                            } else {
                                stringResource(R.string.personal_agent_host_cloud_desc_unpaired)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = tokens.ink3,
                        )
                    }
                },
                trailingIcon = {
                    if (isCloudSelected) {
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
                    if (cloudAvailable && !isCloudSelected) {
                        onSelectCloud(true)
                    }
                },
            )
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
                        text = stringResource(R.string.personal_agent_host_menu_gateway_settings),
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
