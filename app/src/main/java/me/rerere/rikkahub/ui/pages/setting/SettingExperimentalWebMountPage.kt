package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Globe02
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.agent.webmount.core.WebMountAuthMethod
import me.rerere.rikkahub.data.agent.webmount.core.WebMountCapability
import me.rerere.rikkahub.data.agent.webmount.core.WebMountManager
import me.rerere.rikkahub.data.agent.webmount.core.WebMountStationState
import me.rerere.rikkahub.data.agent.webmount.core.WebMountStatus
import me.rerere.rikkahub.ui.components.ui.workspaceColors
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.utils.plus
import org.koin.compose.koinInject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SettingExperimentalWebMountPage(
    webMountManager: WebMountManager = koinInject(),
) {
    val states by webMountManager.states.collectAsStateWithLifecycle()
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    val navController = LocalNavController.current
    // Track which station ids are currently probing so the same probe button
    // can be disabled per-station while leaving siblings interactive.
    var busyStations by remember { mutableStateOf<Set<String>>(emptySet()) }

    ExperimentalSettingsScaffold(
        title = stringResource(R.string.setting_webmount_title),
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding + PaddingValues(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                ExperimentHeroCard(
                    icon = { Icon(HugeIcons.Globe02, contentDescription = null) },
                    title = stringResource(R.string.setting_webmount_title),
                    description = stringResource(R.string.setting_webmount_desc),
                    trailing = { /* no global toggle — each station owns its enable state */ },
                )
            }
            item {
                ExperimentNote(text = stringResource(R.string.setting_webmount_about_note))
            }
            item {
                ExperimentSectionCard(
                    title = stringResource(R.string.setting_webmount_section_stations),
                ) {
                    val stationStates = states.values.toList()
                    if (stationStates.isEmpty()) {
                        ExperimentNote(text = stringResource(R.string.setting_webmount_no_stations))
                    } else {
                        stationStates.forEachIndexed { index, state ->
                            WebMountStationRow(
                                state = state,
                                busy = state.id in busyStations,
                                onConfigure = {
                                    configureFor(state.id)?.let { navController.navigate(it) }
                                },
                                onProbe = {
                                    busyStations = busyStations + state.id
                                    scope.launch {
                                        runCatching { webMountManager.probe(state.id) }
                                            .onFailure { toaster.show(it.message ?: it.toString()) }
                                        busyStations = busyStations - state.id
                                    }
                                },
                                onWriteProbe = {
                                    busyStations = busyStations + state.id
                                    scope.launch {
                                        runCatching { webMountManager.runWriteProbe(state.id) }
                                            .onFailure { toaster.show(it.message ?: it.toString()) }
                                        busyStations = busyStations - state.id
                                    }
                                },
                            )
                            if (index != stationStates.lastIndex) {
                                ExperimentDivider()
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Detail page for a station, if it has one. Returns null for stations whose
 * configuration is fully inline in the station card (login + probe buttons).
 *
 * Phase 1 M1.6 onwards: sites with extra config (e.g. Feishu app credentials,
 * GitHub default org) get their own per-station detail page added here.
 */
@Suppress("UNUSED_PARAMETER")
private fun configureFor(stationId: String): me.rerere.rikkahub.Screen? = null

@Composable
private fun WebMountStationRow(
    state: WebMountStationState,
    busy: Boolean,
    onConfigure: () -> Unit,
    onProbe: () -> Unit,
    onWriteProbe: () -> Unit,
) {
    val workspace = workspaceColors()
    val canWriteProbe =
        state.capability == WebMountCapability.READ_ONLY ||
            state.capability == WebMountCapability.READ_WRITE
    val hasConfigureTarget = configureFor(state.id) != null

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                modifier = Modifier.size(34.dp),
                shape = RoundedCornerShape(8.dp),
                color = workspace.row,
                contentColor = workspace.muted,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(iconForStation(state.id), contentDescription = null)
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = state.displayName,
                    style = MaterialTheme.typography.bodyLarge,
                    color = workspace.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(stationIdKey(state.id)),
                    style = MaterialTheme.typography.bodySmall,
                    color = workspace.faint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            WebMountStatusPill(state.status)
        }

        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            WebMountCapabilityPill(state.capability)
            state.authMethods.forEach { method ->
                WebMountAuthPill(method)
            }
        }

        state.message?.takeIf { it.isNotBlank() }?.let { message ->
            ExperimentNote(text = message)
        }

        ExperimentActionRow {
            if (hasConfigureTarget) {
                ExperimentActionButton(
                    text = stringResource(R.string.setting_webmount_configure),
                    primary = true,
                    enabled = !busy,
                    onClick = onConfigure,
                )
            }
            ExperimentActionButton(
                text = stringResource(R.string.setting_webmount_probe),
                enabled = !busy,
                onClick = onProbe,
            )
            if (canWriteProbe) {
                ExperimentActionButton(
                    text = stringResource(R.string.setting_webmount_write_probe),
                    enabled = !busy,
                    onClick = onWriteProbe,
                )
            }
        }

        ExperimentStatusRow(
            label = stringResource(R.string.setting_webmount_last_updated),
            value = formatTimestamp(state.updatedAtMillis),
        )
    }
}

@Composable
private fun WebMountStatusPill(status: WebMountStatus) {
    val workspace = workspaceColors()
    val (containerAlpha, color, labelRes) = when (status) {
        WebMountStatus.READ_WRITE -> Triple(0.12f, workspace.blue, R.string.setting_webmount_status_read_write)
        WebMountStatus.READ_ONLY -> Triple(0.12f, workspace.blue, R.string.setting_webmount_status_read_only)
        WebMountStatus.PROBING -> Triple(0.10f, workspace.muted, R.string.setting_webmount_status_probing)
        WebMountStatus.LOGIN_REQUIRED -> Triple(0.12f, workspace.muted, R.string.setting_webmount_status_login_required)
        WebMountStatus.DEGRADED -> Triple(0.14f, workspace.muted, R.string.setting_webmount_status_degraded)
        WebMountStatus.ERROR -> Triple(0.14f, MaterialTheme.colorScheme.error, R.string.setting_webmount_status_error)
        WebMountStatus.NOT_CONFIGURED -> Triple(0.08f, workspace.faint, R.string.setting_webmount_status_not_configured)
    }
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = color.copy(alpha = containerAlpha),
        contentColor = color,
        border = BorderStroke(1.dp, color.copy(alpha = 0.25f)),
    ) {
        Text(
            text = stringResource(labelRes),
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun WebMountCapabilityPill(capability: WebMountCapability) {
    val workspace = workspaceColors()
    val labelRes = when (capability) {
        WebMountCapability.READ_WRITE -> R.string.setting_webmount_capability_read_write
        WebMountCapability.READ_ONLY -> R.string.setting_webmount_capability_read_only
        WebMountCapability.NONE -> R.string.setting_webmount_capability_none
    }
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = workspace.row,
        contentColor = workspace.muted,
        border = BorderStroke(1.dp, workspace.hairline),
    ) {
        Text(
            text = stringResource(labelRes),
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun WebMountAuthPill(method: WebMountAuthMethod) {
    val workspace = workspaceColors()
    val labelRes = when (method) {
        WebMountAuthMethod.COOKIE -> R.string.setting_webmount_auth_cookie
        WebMountAuthMethod.OAUTH -> R.string.setting_webmount_auth_oauth
        WebMountAuthMethod.ANONYMOUS -> R.string.setting_webmount_auth_anonymous
    }
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = workspace.row,
        contentColor = workspace.faint,
        border = BorderStroke(1.dp, workspace.hairline),
    ) {
        Text(
            text = stringResource(labelRes),
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

@Suppress("UNUSED_PARAMETER")
private fun iconForStation(stationId: String) = HugeIcons.Globe02

@Suppress("UNUSED_PARAMETER")
private fun stationIdKey(stationId: String): Int = R.string.setting_webmount_station_generic_desc

private fun formatTimestamp(ms: Long): String =
    if (ms <= 0L) {
        "—"
    } else {
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(ms))
    }
