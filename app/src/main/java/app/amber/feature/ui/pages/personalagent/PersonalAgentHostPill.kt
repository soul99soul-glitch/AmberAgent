package app.amber.feature.ui.pages.personalagent

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.amber.agent.R
import app.amber.feature.ui.pages.chat.LocalChatTheme
import app.amber.feature.ui.theme.LocalAmberTokens
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.Cloud
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Smartphone

/**
 * Two-option host switcher pill for the Personal Agent top bar: Local vs Cloud.
 *
 * Cloud is only selectable when a gateway connection is paired (`cloudAvailable == true`).
 * Switching delegates directly to [onSelectCloud], which calls `MacGatewayRepository.setCloudDialogueEnabled`.
 */
@Composable
fun PersonalAgentHostPill(
    isCloudSelected: Boolean,
    cloudAvailable: Boolean,
    gatewayName: String?,
    onSelectCloud: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalAmberTokens.current
    val chatTheme = LocalChatTheme.current
    var expanded by remember { mutableStateOf(false) }

    val localLabel = stringResource(R.string.personal_agent_host_local)
    val cloudLabel = stringResource(R.string.personal_agent_host_cloud)
    val activeLabel = if (isCloudSelected) cloudLabel else localLabel
    val switchA11y = stringResource(R.string.personal_agent_host_switch_a11y, activeLabel)
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(durationMillis = 200),
        label = "personalAgentHostChevron",
    )

    Box(modifier = modifier) {
        // Outer 48dp touch box keeps touch ergonomics accessible while keeping the visual pill compact.
        Box(
            modifier = Modifier
                .heightIn(min = 48.dp)
                .widthIn(min = 48.dp)
                .clip(CircleShape)
                .testTag("personal-agent-host-pill")
                .clickable(role = Role.Button) { expanded = true }
                .semantics { contentDescription = switchA11y }
                .padding(horizontal = 4.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            Row(
                modifier = Modifier
                    .heightIn(min = 32.dp)
                    .clip(CircleShape)
                    .background(if (isCloudSelected) chatTheme.accentTint else tokens.surface2)
                    .border(
                        width = 1.dp,
                        color = if (isCloudSelected) chatTheme.accent.copy(alpha = 0.35f) else tokens.line2,
                        shape = CircleShape,
                    )
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Icon(
                    imageVector = if (isCloudSelected) Lucide.Cloud else Lucide.Smartphone,
                    contentDescription = null,
                    tint = if (isCloudSelected) chatTheme.accent else tokens.ink,
                    modifier = Modifier.size(14.dp),
                )
                Text(
                    text = activeLabel,
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = FontWeight.Medium,
                    ),
                    color = if (isCloudSelected) chatTheme.accent else tokens.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Icon(
                    imageVector = Lucide.ChevronDown,
                    contentDescription = null,
                    tint = if (isCloudSelected) chatTheme.accent else tokens.ink3,
                    modifier = Modifier
                        .size(13.dp)
                        .rotate(chevronRotation),
                )
            }
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
                            tint = chatTheme.accent,
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
                            tint = chatTheme.accent,
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
        }
    }
}
