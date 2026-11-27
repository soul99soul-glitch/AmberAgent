package app.amber.feature.ui.pages.proactive

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.amber.agent.R
import app.amber.agent.Screen
import app.amber.core.utils.plus
import app.amber.feature.macgateway.CloudProactiveMonitorRecord
import app.amber.feature.macgateway.CloudProactiveMutedTopic
import app.amber.feature.macgateway.CloudProactiveRestraintSnapshot
import app.amber.feature.macgateway.CloudProactiveRunHistoryEntry
import app.amber.feature.macgateway.CloudProactiveWakeupRecord
import app.amber.feature.macgateway.CloudProactiveWebhookRecord
import app.amber.feature.macgateway.ProactiveContract
import app.amber.feature.ui.components.ds.amberCanvas
import app.amber.feature.ui.components.nav.BackButton
import app.amber.feature.ui.components.ui.Switch
import app.amber.feature.ui.components.ui.WorkspaceTopBar
import app.amber.feature.ui.components.ui.workspaceColors
import app.amber.feature.ui.context.LocalNavController
import app.amber.feature.ui.pages.setting.ExperimentNote
import app.amber.feature.ui.pages.setting.ExperimentSectionCard
import com.composables.icons.lucide.AlarmClock
import com.composables.icons.lucide.Github
import com.composables.icons.lucide.Info
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.RefreshCw
import com.composables.icons.lucide.Trash2
import java.text.DateFormat
import java.util.Date
import org.koin.androidx.compose.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CloudProactiveCenterPage(
    vm: CloudProactiveCenterVM = koinViewModel(),
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val navController = LocalNavController.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val pullState = rememberPullToRefreshState()

    var pendingDeleteMonitor by remember { mutableStateOf<CloudProactiveMonitorRecord?>(null) }
    var pendingDeleteWebhook by remember { mutableStateOf<CloudProactiveWebhookRecord?>(null) }

    Scaffold(
        topBar = {
            WorkspaceTopBar(
                title = stringResource(R.string.proactive_center_title),
                navigationIcon = { BackButton() },
                actions = {
                    if (state.isPaired) {
                        IconButton(
                            onClick = { vm.refresh() },
                            enabled = !state.loading && !state.refreshing,
                            modifier = Modifier
                                .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                                .testTag("proactive-center-refresh"),
                        ) {
                            Icon(
                                imageVector = Lucide.RefreshCw,
                                contentDescription = stringResource(R.string.proactive_center_refresh),
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        modifier = Modifier
            .nestedScroll(scrollBehavior.nestedScrollConnection)
            .amberCanvas(),
        containerColor = Color.Transparent,
    ) { innerPadding ->
        if (!state.isPaired) {
            UnpairedProactiveCenterBody(
                innerPadding = innerPadding,
                onOpenGatewaySettings = {
                    navController.navigate(Screen.SettingExperimentalMacGateway)
                },
            )
            return@Scaffold
        }

        PullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = { vm.refresh() },
            state = pullState,
            modifier = Modifier.fillMaxSize(),
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("proactive-center-list"),
                contentPadding = innerPadding + PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // Top guidance banner
                item("chat_hint") {
                    ChatCreationHintBanner()
                }

                // Inline notice / unsupported warning
                state.notice?.let { notice ->
                    item("notice") {
                        NoticeBanner(notice = notice)
                    }
                }

                // Initial loading or blocking load error
                val summary = state.summary
                if (state.loading && summary == null) {
                    item("loading") {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 36.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator()
                        }
                    }
                    return@LazyColumn
                }

                val loadError = state.loadError
                if (summary == null && loadError != null) {
                    item("load_error") {
                        ExperimentSectionCard(title = stringResource(R.string.proactive_center_title)) {
                            ExperimentNote(
                                text = describeProactiveError(loadError),
                                error = true,
                            )
                            Button(
                                onClick = { vm.loadSummary(isRefresh = false) },
                                modifier = Modifier.heightIn(min = 48.dp),
                            ) {
                                Text(stringResource(R.string.proactive_detail_retry))
                            }
                        }
                    }
                    return@LazyColumn
                }

                if (summary == null) return@LazyColumn

                // 1. P2 Upcoming Reminders (Wakeups)
                item("wakeups") {
                    WakeupsSection(
                        wakeups = summary.wakeups,
                        unsupported = ProactiveFeature.WAKEUPS in state.unsupportedFeatures,
                        mutatingKeys = state.mutatingKeys,
                        onCancelWakeup = vm::cancelWakeup,
                    )
                }

                // 2. P1+P2 Restraint & Daily Pulse
                item("restraint") {
                    RestraintAndPulseSection(
                        restraint = summary.restraint,
                        quietStartDraft = state.quietStartDraft,
                        quietEndDraft = state.quietEndDraft,
                        pulseTimeDraft = state.pulseTimeDraft,
                        scheduleValidationError = state.scheduleValidationError,
                        restraintUnsupported = ProactiveFeature.RESTRAINT in state.unsupportedFeatures,
                        p2Unsupported = ProactiveFeature.RESTRAINT_P2 in state.unsupportedFeatures,
                        mutatingKeys = state.mutatingKeys,
                        onQuietHoursToggle = vm::setQuietHoursEnabled,
                        onQuietStartChanged = vm::onQuietStartChanged,
                        onQuietEndChanged = vm::onQuietEndChanged,
                        onDailyMaxPushesChanged = vm::setDailyMaxPushes,
                        onPulseToggle = vm::setPulseEnabled,
                        onPulseTimeChanged = vm::onPulseTimeChanged,
                        onCommitmentToggle = vm::setCommitmentExtractionEnabled,
                        onSaveTimes = vm::saveScheduleTimes,
                    )
                }

                // 3. P1+P3 Monitors + GitHub Release quick subscribe
                item("monitors") {
                    MonitorsSection(
                        monitors = summary.monitors,
                        deliveryModeUnsupported = ProactiveFeature.MONITOR_DELIVERY_MODE in state.unsupportedFeatures,
                        githubCreateUnsupported = ProactiveFeature.CREATE_GITHUB_MONITOR in state.unsupportedFeatures,
                        mutatingKeys = state.mutatingKeys,
                        githubRepoInput = state.githubRepoInput,
                        githubDeliveryMode = state.githubDeliveryMode,
                        githubValidationError = state.githubValidationError,
                        creatingGithubMonitor = state.creatingGithubMonitor,
                        onGithubRepoInputChanged = vm::onGithubRepoInputChanged,
                        onGithubDeliveryModeChanged = vm::onGithubDeliveryModeChanged,
                        onSubmitGithubMonitor = vm::submitGithubReleaseMonitor,
                        onToggleMonitor = vm::setMonitorEnabled,
                        onChangeDeliveryMode = vm::setMonitorDeliveryMode,
                        onRequestDeleteMonitor = { pendingDeleteMonitor = it },
                    )
                }

                // 4. P1 Webhooks
                item("webhooks") {
                    WebhooksSection(
                        webhooks = summary.webhooks,
                        unsupported = ProactiveFeature.WEBHOOKS in state.unsupportedFeatures,
                        mutatingKeys = state.mutatingKeys,
                        onToggleWebhook = vm::setWebhookEnabled,
                        onRequestDeleteWebhook = { pendingDeleteWebhook = it },
                    )
                }

                // 5. P1 Muted Topics
                item("muted_topics") {
                    MutedTopicsSection(
                        mutedTopics = summary.restraint.mutedTopics,
                        unsupported = ProactiveFeature.UNMUTE in state.unsupportedFeatures,
                        mutatingKeys = state.mutatingKeys,
                        onUnmute = vm::unmuteTopic,
                    )
                }

                // 6. P1-P3 Recent Runs
                item("recent_runs") {
                    RecentRunsSection(runs = summary.recentRuns)
                }
            }
        }
    }

    pendingDeleteMonitor?.let { monitor ->
        AlertDialog(
            onDismissRequest = { pendingDeleteMonitor = null },
            title = { Text(stringResource(R.string.proactive_center_confirm_delete_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.proactive_center_confirm_delete_monitor,
                        monitor.title.ifBlank { monitor.id },
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDeleteMonitor = null
                        vm.deleteMonitor(monitor.id)
                    },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(
                        text = stringResource(R.string.proactive_center_monitor_delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { pendingDeleteMonitor = null },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(stringResource(R.string.proactive_center_cancel))
                }
            },
        )
    }

    pendingDeleteWebhook?.let { webhook ->
        AlertDialog(
            onDismissRequest = { pendingDeleteWebhook = null },
            title = { Text(stringResource(R.string.proactive_center_confirm_delete_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.proactive_center_confirm_delete_webhook,
                        webhook.name.ifBlank { webhook.id },
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDeleteWebhook = null
                        vm.deleteWebhook(webhook.id)
                    },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(
                        text = stringResource(R.string.proactive_center_monitor_delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { pendingDeleteWebhook = null },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(stringResource(R.string.proactive_center_cancel))
                }
            },
        )
    }
}

@Composable
private fun UnpairedProactiveCenterBody(
    innerPadding: PaddingValues,
    onOpenGatewaySettings: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(innerPadding)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ExperimentSectionCard(title = stringResource(R.string.proactive_center_unpaired_title)) {
            ExperimentNote(text = stringResource(R.string.proactive_center_unpaired_body))
            Button(
                onClick = onOpenGatewaySettings,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(stringResource(R.string.proactive_center_go_pair))
            }
        }
    }
}

@Composable
private fun ChatCreationHintBanner() {
    val scheme = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = scheme.primaryContainer.copy(alpha = 0.55f),
        contentColor = scheme.onPrimaryContainer,
        border = BorderStroke(1.dp, scheme.primary.copy(alpha = 0.2f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                imageVector = Lucide.Info,
                contentDescription = null,
                modifier = Modifier
                    .padding(top = 2.dp)
                    .size(16.dp),
            )
            Text(
                text = stringResource(R.string.proactive_center_chat_hint),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun NoticeBanner(notice: ProactiveCenterNotice) {
    val isError = notice is ProactiveCenterNotice.Error || notice is ProactiveCenterNotice.UnsupportedFeature
    val text = when (notice) {
        ProactiveCenterNotice.Saved -> stringResource(R.string.proactive_center_saved)
        ProactiveCenterNotice.WakeupCancelled -> stringResource(R.string.proactive_center_wakeup_cancelled)
        ProactiveCenterNotice.MonitorDeleted -> stringResource(R.string.proactive_center_monitor_deleted)
        ProactiveCenterNotice.WebhookDeleted -> stringResource(R.string.proactive_center_webhook_deleted)
        ProactiveCenterNotice.TopicUnmuted -> stringResource(R.string.proactive_center_unmuted)
        is ProactiveCenterNotice.GithubSubscribed ->
            stringResource(R.string.proactive_center_github_subscribed, notice.slug)
        is ProactiveCenterNotice.UnsupportedFeature ->
            stringResource(R.string.proactive_center_error_unsupported)
        is ProactiveCenterNotice.Error -> describeProactiveError(notice.error)
    }
    ExperimentNote(text = text, error = isError)
}

@Composable
private fun WakeupsSection(
    wakeups: List<CloudProactiveWakeupRecord>,
    unsupported: Boolean,
    mutatingKeys: Set<String>,
    onCancelWakeup: (String) -> Unit,
) {
    val workspace = workspaceColors()
    ExperimentSectionCard(title = stringResource(R.string.proactive_center_section_wakeups)) {
        if (unsupported) {
            ExperimentNote(text = stringResource(R.string.proactive_center_error_unsupported), error = true)
        }
        if (wakeups.isEmpty()) {
            ExperimentNote(text = stringResource(R.string.proactive_center_wakeups_empty))
            return@ExperimentSectionCard
        }
        wakeups.forEach { wakeup ->
            val busy = "wakeup:${wakeup.id}" in mutatingKeys
            val sourceLabel = when (CloudProactiveCenterVM.classifyWakeupSource(wakeup.kind)) {
                WakeupSourceKind.COMMITMENT -> stringResource(R.string.proactive_center_wakeup_source_commitment)
                WakeupSourceKind.SNOOZE -> stringResource(R.string.proactive_center_wakeup_source_snooze)
                WakeupSourceKind.AGENT -> stringResource(R.string.proactive_center_wakeup_source_agent)
                WakeupSourceKind.UNKNOWN -> wakeup.kind.ifBlank {
                    stringResource(R.string.proactive_center_wakeup_source_agent)
                }
            }
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                color = workspace.row,
                border = BorderStroke(1.dp, workspace.hairline),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Lucide.AlarmClock,
                            contentDescription = null,
                            tint = workspace.muted,
                            modifier = Modifier.size(16.dp),
                        )
                        Text(
                            text = wakeup.title.ifBlank { wakeup.id },
                            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                            color = workspace.ink,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        SmallTagPill(text = sourceLabel)
                        if (wakeup.triggerAtEpochMs > 0L) {
                            SmallTagPill(text = formatDateTime(wakeup.triggerAtEpochMs))
                        }
                    }
                    if (wakeup.detail.isNotBlank()) {
                        Text(
                            text = wakeup.detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = workspace.muted,
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        OutlinedButton(
                            onClick = { onCancelWakeup(wakeup.id) },
                            enabled = !busy,
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("proactive-wakeup-cancel-${wakeup.id}"),
                        ) {
                            Text(stringResource(R.string.proactive_center_wakeup_cancel))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RestraintAndPulseSection(
    restraint: CloudProactiveRestraintSnapshot,
    quietStartDraft: String,
    quietEndDraft: String,
    pulseTimeDraft: String,
    scheduleValidationError: Boolean,
    restraintUnsupported: Boolean,
    p2Unsupported: Boolean,
    mutatingKeys: Set<String>,
    onQuietHoursToggle: (Boolean) -> Unit,
    onQuietStartChanged: (String) -> Unit,
    onQuietEndChanged: (String) -> Unit,
    onDailyMaxPushesChanged: (Int) -> Unit,
    onPulseToggle: (Boolean) -> Unit,
    onPulseTimeChanged: (String) -> Unit,
    onCommitmentToggle: (Boolean) -> Unit,
    onSaveTimes: () -> Unit,
) {
    val workspace = workspaceColors()
    ExperimentSectionCard(title = stringResource(R.string.proactive_center_section_restraint)) {
        if (restraintUnsupported || p2Unsupported) {
            ExperimentNote(text = stringResource(R.string.proactive_center_error_unsupported), error = true)
        }

        // Quiet hours toggle
        ToggleRow(
            title = stringResource(R.string.proactive_center_quiet_hours),
            subtitle = stringResource(R.string.proactive_center_quiet_hours_desc),
            checked = restraint.quietHoursEnabled,
            enabled = "restraint:quietHoursEnabled" !in mutatingKeys,
            onCheckedChange = onQuietHoursToggle,
        )

        // Quiet hours start/end inputs
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = quietStartDraft,
                onValueChange = onQuietStartChanged,
                label = { Text(stringResource(R.string.proactive_center_quiet_start)) },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = quietEndDraft,
                onValueChange = onQuietEndChanged,
                label = { Text(stringResource(R.string.proactive_center_quiet_end)) },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
        }

        // Daily push limit stepper (1..100)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = stringResource(R.string.proactive_center_daily_cap),
                    style = MaterialTheme.typography.bodyLarge,
                    color = workspace.ink,
                )
                Text(
                    text = stringResource(
                        R.string.proactive_center_daily_cap_usage,
                        restraint.pushesToday,
                        restraint.dailyMaxPushes,
                        restraint.remainingToday,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = workspace.muted,
                )
            }
            val capBusy = "restraint:dailyMaxPushes" in mutatingKeys
            OutlinedButton(
                onClick = { onDailyMaxPushesChanged(restraint.dailyMaxPushes - 1) },
                enabled = !capBusy && restraint.dailyMaxPushes > CloudProactiveCenterVM.MIN_DAILY_MAX_PUSHES,
                modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp),
                contentPadding = PaddingValues(horizontal = 12.dp),
            ) {
                Text("-")
            }
            Text(
                text = restraint.dailyMaxPushes.toString(),
                style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace),
                color = workspace.ink,
            )
            OutlinedButton(
                onClick = { onDailyMaxPushesChanged(restraint.dailyMaxPushes + 1) },
                enabled = !capBusy && restraint.dailyMaxPushes < CloudProactiveCenterVM.MAX_DAILY_MAX_PUSHES,
                modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp),
                contentPadding = PaddingValues(horizontal = 12.dp),
            ) {
                Text("+")
            }
        }

        // Daily Pulse toggle & time (P2)
        ToggleRow(
            title = stringResource(R.string.proactive_center_pulse),
            subtitle = if (restraint.bufferedPulseCount > 0) {
                stringResource(R.string.proactive_center_pulse_desc) + " · " +
                    stringResource(R.string.proactive_center_pulse_buffered, restraint.bufferedPulseCount)
            } else {
                stringResource(R.string.proactive_center_pulse_desc)
            },
            checked = restraint.pulseEnabled,
            enabled = "restraint:pulseEnabled" !in mutatingKeys,
            onCheckedChange = onPulseToggle,
        )

        OutlinedTextField(
            value = pulseTimeDraft,
            onValueChange = onPulseTimeChanged,
            label = { Text(stringResource(R.string.proactive_center_pulse_time)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        if (scheduleValidationError) {
            ExperimentNote(text = stringResource(R.string.proactive_center_invalid_time), error = true)
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            Button(
                onClick = onSaveTimes,
                enabled = "restraint:schedule" !in mutatingKeys,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(stringResource(R.string.proactive_center_save_schedule))
            }
        }

        // Commitment extraction toggle (P2)
        ToggleRow(
            title = stringResource(R.string.proactive_center_commitment),
            subtitle = stringResource(R.string.proactive_center_commitment_desc),
            checked = restraint.commitmentExtractionEnabled,
            enabled = "restraint:commitmentExtractionEnabled" !in mutatingKeys,
            onCheckedChange = onCommitmentToggle,
        )
    }
}

@Composable
private fun MonitorsSection(
    monitors: List<CloudProactiveMonitorRecord>,
    deliveryModeUnsupported: Boolean,
    githubCreateUnsupported: Boolean,
    mutatingKeys: Set<String>,
    githubRepoInput: String,
    githubDeliveryMode: String,
    githubValidationError: GithubRepoValidationError?,
    creatingGithubMonitor: Boolean,
    onGithubRepoInputChanged: (String) -> Unit,
    onGithubDeliveryModeChanged: (String) -> Unit,
    onSubmitGithubMonitor: () -> Unit,
    onToggleMonitor: (String, Boolean) -> Unit,
    onChangeDeliveryMode: (String, String) -> Unit,
    onRequestDeleteMonitor: (CloudProactiveMonitorRecord) -> Unit,
) {
    val workspace = workspaceColors()
    ExperimentSectionCard(title = stringResource(R.string.proactive_center_section_monitors)) {
        if (deliveryModeUnsupported || githubCreateUnsupported) {
            ExperimentNote(text = stringResource(R.string.proactive_center_error_unsupported), error = true)
        }

        // Quick GitHub Release subscription form (P3)
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
            color = workspace.row,
            border = BorderStroke(1.dp, workspace.hairline),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Lucide.Github,
                        contentDescription = null,
                        tint = workspace.ink,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = stringResource(R.string.proactive_center_github_title),
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = workspace.ink,
                    )
                }
                OutlinedTextField(
                    value = githubRepoInput,
                    onValueChange = onGithubRepoInputChanged,
                    placeholder = { Text(stringResource(R.string.proactive_center_github_placeholder)) },
                    singleLine = true,
                    isError = githubValidationError != null,
                    supportingText = githubValidationError?.let { err ->
                        {
                            Text(
                                when (err) {
                                    GithubRepoValidationError.EMPTY,
                                    GithubRepoValidationError.INVALID_FORMAT,
                                    -> stringResource(R.string.proactive_center_github_err_format)
                                    GithubRepoValidationError.INVALID_OWNER ->
                                        stringResource(R.string.proactive_center_github_err_owner)
                                    GithubRepoValidationError.INVALID_REPO ->
                                        stringResource(R.string.proactive_center_github_err_repo)
                                },
                            )
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("proactive-github-repo-input"),
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    FilterChip(
                        selected = githubDeliveryMode == ProactiveContract.DELIVERY_MODE_IMMEDIATE,
                        onClick = { onGithubDeliveryModeChanged(ProactiveContract.DELIVERY_MODE_IMMEDIATE) },
                        label = { Text(stringResource(R.string.proactive_center_monitor_delivery_immediate)) },
                        modifier = Modifier.heightIn(min = 48.dp),
                    )
                    FilterChip(
                        selected = githubDeliveryMode == ProactiveContract.DELIVERY_MODE_DIGEST,
                        onClick = { onGithubDeliveryModeChanged(ProactiveContract.DELIVERY_MODE_DIGEST) },
                        label = { Text(stringResource(R.string.proactive_center_monitor_delivery_digest)) },
                        modifier = Modifier.heightIn(min = 48.dp),
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    Button(
                        onClick = onSubmitGithubMonitor,
                        enabled = !creatingGithubMonitor && githubRepoInput.isNotBlank() && githubValidationError == null,
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("proactive-github-subscribe-btn"),
                    ) {
                        if (creatingGithubMonitor) {
                            CircularProgressIndicator(
                                modifier = Modifier
                                    .padding(end = 6.dp)
                                    .size(14.dp),
                                strokeWidth = 2.dp,
                            )
                        }
                        Text(stringResource(R.string.proactive_center_github_subscribe))
                    }
                }
            }
        }

        if (monitors.isEmpty()) {
            ExperimentNote(text = stringResource(R.string.proactive_center_monitors_empty))
            return@ExperimentSectionCard
        }

        monitors.forEach { monitor ->
            val toggleBusy = "monitor:toggle:${monitor.id}" in mutatingKeys
            val deliveryBusy = "monitor:delivery:${monitor.id}" in mutatingKeys
            val deleteBusy = "monitor:delete:${monitor.id}" in mutatingKeys
            val isDigest = monitor.deliveryMode.equals(ProactiveContract.DELIVERY_MODE_DIGEST, ignoreCase = true)

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                color = workspace.row,
                border = BorderStroke(1.dp, workspace.hairline),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text(
                                text = monitor.title.ifBlank { monitor.url.ifBlank { monitor.id } },
                                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                                color = workspace.ink,
                            )
                            if (monitor.url.isNotBlank()) {
                                Text(
                                    text = monitor.url,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = workspace.muted,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Text(
                                text = buildString {
                                    append(stringResource(R.string.proactive_center_monitor_interval, monitor.intervalMinutes))
                                    monitor.lastOutcome?.takeIf { it.isNotBlank() }?.let {
                                        append(" · ")
                                        append(it)
                                    }
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = workspace.faint,
                            )
                        }
                        Switch(
                            checked = monitor.enabled,
                            onCheckedChange = { if (!toggleBusy) onToggleMonitor(monitor.id, it) },
                        )
                    }

                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        FilterChip(
                            selected = !isDigest,
                            enabled = !deliveryBusy,
                            onClick = {
                                if (isDigest) {
                                    onChangeDeliveryMode(monitor.id, ProactiveContract.DELIVERY_MODE_IMMEDIATE)
                                }
                            },
                            label = { Text(stringResource(R.string.proactive_center_monitor_delivery_immediate)) },
                            modifier = Modifier.heightIn(min = 48.dp),
                        )
                        FilterChip(
                            selected = isDigest,
                            enabled = !deliveryBusy,
                            onClick = {
                                if (!isDigest) {
                                    onChangeDeliveryMode(monitor.id, ProactiveContract.DELIVERY_MODE_DIGEST)
                                }
                            },
                            label = { Text(stringResource(R.string.proactive_center_monitor_delivery_digest)) },
                            modifier = Modifier.heightIn(min = 48.dp),
                        )
                        TextButton(
                            onClick = { onRequestDeleteMonitor(monitor) },
                            enabled = !deleteBusy,
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) {
                            Icon(
                                imageVector = Lucide.Trash2,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier
                                    .padding(end = 4.dp)
                                    .size(15.dp),
                            )
                            Text(
                                text = stringResource(R.string.proactive_center_monitor_delete),
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WebhooksSection(
    webhooks: List<CloudProactiveWebhookRecord>,
    unsupported: Boolean,
    mutatingKeys: Set<String>,
    onToggleWebhook: (String, Boolean) -> Unit,
    onRequestDeleteWebhook: (CloudProactiveWebhookRecord) -> Unit,
) {
    val workspace = workspaceColors()
    ExperimentSectionCard(title = stringResource(R.string.proactive_center_section_webhooks)) {
        if (unsupported) {
            ExperimentNote(text = stringResource(R.string.proactive_center_error_unsupported), error = true)
        }
        if (webhooks.isEmpty()) {
            ExperimentNote(text = stringResource(R.string.proactive_center_webhooks_empty))
            return@ExperimentSectionCard
        }
        webhooks.forEach { webhook ->
            val toggleBusy = "webhook:toggle:${webhook.id}" in mutatingKeys
            val deleteBusy = "webhook:delete:${webhook.id}" in mutatingKeys
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                color = workspace.row,
                border = BorderStroke(1.dp, workspace.hairline),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text(
                                text = webhook.name.ifBlank { webhook.id },
                                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                                color = workspace.ink,
                            )
                            if (webhook.instruction.isNotBlank()) {
                                Text(
                                    text = webhook.instruction,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = workspace.muted,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Text(
                                text = stringResource(R.string.proactive_center_webhook_triggers, webhook.triggerCount),
                                style = MaterialTheme.typography.labelSmall,
                                color = workspace.faint,
                            )
                        }
                        Switch(
                            checked = webhook.enabled,
                            onCheckedChange = { if (!toggleBusy) onToggleWebhook(webhook.id, it) },
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(
                            onClick = { onRequestDeleteWebhook(webhook) },
                            enabled = !deleteBusy,
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) {
                            Icon(
                                imageVector = Lucide.Trash2,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier
                                    .padding(end = 4.dp)
                                    .size(15.dp),
                            )
                            Text(
                                text = stringResource(R.string.proactive_center_monitor_delete),
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MutedTopicsSection(
    mutedTopics: List<CloudProactiveMutedTopic>,
    unsupported: Boolean,
    mutatingKeys: Set<String>,
    onUnmute: (String) -> Unit,
) {
    val workspace = workspaceColors()
    ExperimentSectionCard(title = stringResource(R.string.proactive_center_section_muted)) {
        if (unsupported) {
            ExperimentNote(text = stringResource(R.string.proactive_center_error_unsupported), error = true)
        }
        if (mutedTopics.isEmpty()) {
            ExperimentNote(text = stringResource(R.string.proactive_center_muted_empty))
            return@ExperimentSectionCard
        }
        mutedTopics.forEach { muted ->
            val busy = "unmute:${muted.topicKey}" in mutatingKeys
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = muted.topicKey,
                        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                        color = workspace.ink,
                    )
                    if (muted.untilEpochMs > 0L) {
                        Text(
                            text = stringResource(
                                R.string.proactive_center_muted_until,
                                formatDateTime(muted.untilEpochMs),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = workspace.muted,
                        )
                    }
                }
                OutlinedButton(
                    onClick = { onUnmute(muted.topicKey) },
                    enabled = !busy,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(stringResource(R.string.proactive_center_unmute))
                }
            }
        }
    }
}

@Composable
private fun RecentRunsSection(
    runs: List<CloudProactiveRunHistoryEntry>,
) {
    val workspace = workspaceColors()
    ExperimentSectionCard(title = stringResource(R.string.proactive_center_section_runs)) {
        if (runs.isEmpty()) {
            ExperimentNote(text = stringResource(R.string.proactive_center_runs_empty))
            return@ExperimentSectionCard
        }
        runs.forEach { run ->
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                color = workspace.row,
                border = BorderStroke(1.dp, workspace.hairline),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = run.title.ifBlank { run.topicKey.ifBlank { run.id } },
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                            color = workspace.ink,
                            modifier = Modifier.weight(1f),
                        )
                        if (run.outcome.isNotBlank()) {
                            SmallTagPill(text = run.outcome)
                        }
                    }
                    if (run.summary.isNotBlank()) {
                        Text(
                            text = run.summary,
                            style = MaterialTheme.typography.bodySmall,
                            color = workspace.muted,
                        )
                    }
                    if (run.timestampEpochMs > 0L) {
                        Text(
                            text = formatDateTime(run.timestampEpochMs),
                            style = MaterialTheme.typography.labelSmall,
                            color = workspace.faint,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val workspace = workspaceColors()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = workspace.ink,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = workspace.muted,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = { if (enabled) onCheckedChange(it) },
        )
    }
}

@Composable
private fun SmallTagPill(text: String) {
    val workspace = workspaceColors()
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = workspace.paper,
        border = BorderStroke(1.dp, workspace.hairline),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = workspace.muted,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

@Composable
private fun describeProactiveError(error: ProactiveCenterError): String = when (error) {
    ProactiveCenterError.UnsupportedOnServer -> stringResource(R.string.proactive_center_error_unsupported)
    ProactiveCenterError.Unreachable -> stringResource(R.string.proactive_center_error_unreachable)
    ProactiveCenterError.Unauthorized -> stringResource(R.string.proactive_center_error_unauthorized)
    is ProactiveCenterError.Generic -> stringResource(R.string.proactive_center_error_generic, error.message)
}

private fun formatDateTime(epochMs: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(epochMs))
