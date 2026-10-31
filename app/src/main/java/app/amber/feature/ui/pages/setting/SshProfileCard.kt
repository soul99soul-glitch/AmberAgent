package app.amber.feature.ui.pages.setting

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.CircleAlert
import com.composables.icons.lucide.CircleCheck
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.EllipsisVertical
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Pencil
import com.composables.icons.lucide.RefreshCw
import com.composables.icons.lucide.Server
import com.composables.icons.lucide.Trash2
import app.amber.agent.R
import app.amber.feature.terminal.SshAuthMethod
import app.amber.feature.terminal.SshHostTrust
import app.amber.feature.terminal.SshProfile
import app.amber.feature.terminal.SshRouteResolver
import app.amber.feature.terminal.SshTrustPolicy
import app.amber.feature.ui.components.ui.workspaceColors
import app.amber.feature.ui.theme.LocalAmberType

@Composable
internal fun SshProfileCard(
    profile: SshProfile,
    allProfiles: List<SshProfile> = listOf(profile),
    isDefault: Boolean,
    isSelected: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onMakeDefault: () -> Unit,
    onProbe: () -> Unit,
) {
    val colors = workspaceColors()
    val route = remember(profile, allProfiles) {
        runCatching { SshRouteResolver.resolveRoute(profile.id, allProfiles) }.getOrNull() ?: listOf(profile)
    }
    val trusted = route.all { hop ->
        SshTrustPolicy.evaluate(hop, hop.acceptedHostKeyFingerprint) is SshHostTrust.Trusted
    }
    val trustDescription = stringResource(
        if (trusted) R.string.setting_sandbox_ssh_host_key_trusted
        else R.string.setting_sandbox_ssh_host_key_untrusted,
    )
    val authDescription = stringResource(
        if (profile.authMethod == SshAuthMethod.PASSWORD) {
            R.string.setting_sandbox_ssh_auth_password
        } else {
            R.string.setting_sandbox_ssh_auth_private_key
        },
    )
    var actionsExpanded by remember(profile.id) { mutableStateOf(false) }

    val routeSuffix = if (route.size > 1) {
        " · 跳板: ${route.joinToString(" → ") { it.name }}"
    } else ""

    SandboxSettingsRow(
        title = profile.name,
        summary = "${profile.username}@${profile.host}:${profile.port}$routeSuffix",
        modifier = (if (isSelected) {
            Modifier.background(colors.blueContainer.copy(alpha = 0.32f))
        } else {
            Modifier
        }).semantics { selected = isSelected },
        leading = Lucide.Server,
        onClick = onSelect,
        enabled = enabled,
        trailing = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                if (isDefault) {
                    Surface(
                        color = colors.blueContainer,
                        contentColor = colors.blue,
                        shape = MaterialTheme.shapes.small,
                    ) {
                        Text(
                            text = stringResource(R.string.setting_sandbox_ssh_default),
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            style = LocalAmberType.current.tinyTag,
                        )
                    }
                }
                Icon(
                    imageVector = if (trusted) Lucide.CircleCheck else Lucide.CircleAlert,
                    contentDescription = trustDescription,
                    modifier = Modifier.size(18.dp),
                    tint = if (trusted) colors.green else colors.red,
                )
                Box {
                    IconButton(
                        onClick = { actionsExpanded = true },
                        enabled = enabled,
                    ) {
                        Icon(
                            imageVector = Lucide.EllipsisVertical,
                            contentDescription = stringResource(R.string.skills_page_more_actions),
                        )
                    }
                    DropdownMenu(
                        expanded = actionsExpanded,
                        onDismissRequest = { actionsExpanded = false },
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(3.dp),
                        ) {
                            Text(
                                text = profile.name,
                                style = LocalAmberType.current.body,
                            )
                            Text(
                                text = "${profile.username}@${profile.host}:${profile.port}",
                                style = LocalAmberType.current.meta,
                                color = colors.muted,
                            )
                            Text(
                                text = "$trustDescription · $authDescription",
                                style = LocalAmberType.current.secondary,
                                color = if (trusted) colors.green else colors.red,
                            )
                        }
                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 12.dp),
                            color = colors.hairline.copy(alpha = 0.55f),
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.setting_sandbox_ssh_probe)) },
                            leadingIcon = { Icon(Lucide.RefreshCw, null) },
                            enabled = enabled,
                            onClick = {
                                actionsExpanded = false
                                onProbe()
                            },
                        )
                        if (!isDefault) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.setting_sandbox_ssh_make_default)) },
                                leadingIcon = { Icon(Lucide.Check, null) },
                                enabled = enabled,
                                onClick = {
                                    actionsExpanded = false
                                    onMakeDefault()
                                },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.setting_sandbox_ssh_edit)) },
                            leadingIcon = { Icon(Lucide.Pencil, null) },
                            enabled = enabled,
                            onClick = {
                                actionsExpanded = false
                                onEdit()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.setting_sandbox_ssh_delete)) },
                            leadingIcon = { Icon(Lucide.Trash2, null, tint = colors.red) },
                            enabled = enabled,
                            onClick = {
                                actionsExpanded = false
                                onDelete()
                            },
                        )
                    }
                }
            }
        },
    )
}
