package app.amber.feature.ui.pages.setting

import android.app.TimePickerDialog
import android.text.format.DateFormat
import android.text.format.DateUtils
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.amber.agent.R
import app.amber.feature.macgateway.MacBoardItem
import app.amber.feature.macgateway.MacGatewayException
import app.amber.feature.macgateway.MacGatewayRepository
import app.amber.feature.macgateway.MacGatewaySchedule
import app.amber.feature.macgateway.MacScheduleDraft
import app.amber.feature.ui.components.ui.workspaceColors
import java.text.DateFormatSymbols
import java.util.Calendar
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun MacRemoteTasksSettings(
    repository: MacGatewayRepository,
    state: MacGatewayRepository.State,
    active: Boolean,
    approvalBusy: Boolean,
    approvalError: String?,
    onDecision: (MacBoardItem.Approval, Boolean) -> Unit,
) {
    val generation = state.generation
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showsBoard by remember(generation) { mutableStateOf(false) }
    var draft by remember(generation) { mutableStateOf<MacScheduleDraft?>(null) }
    var runConfirmation by remember(generation) { mutableStateOf<MacGatewaySchedule?>(null) }
    var busy by remember(generation) { mutableStateOf(false) }
    var error by remember(generation) { mutableStateOf<String?>(null) }
    var success by remember(generation) { mutableStateOf<Int?>(null) }
    val online = state.reachable == true
    val boardResult = state.macTasks.board
    val schedulesResult = state.macTasks.schedules
    val board = boardResult?.getOrNull()
    val schedules = schedulesResult?.getOrNull()
    val canManage = active && online && schedules != null && !busy
    val waiting = board?.count { it.column == MacBoardItem.Column.NEEDS_YOU } ?: 0

    fun change(successMessage: Int? = null, block: suspend () -> Unit) {
        if (!canManage) return
        busy = true
        error = null
        success = null
        scope.launch {
            try {
                block()
                success = successMessage
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = failure.describe(context)
            } finally {
                busy = false
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ExperimentSectionCard(title = stringResource(R.string.mac_gateway_board_title)) {
            when {
                !online || board == null -> MacCapabilityNote(state.reachable, boardResult)
                board.isEmpty() -> ExperimentNote(stringResource(R.string.mac_gateway_empty_board))
                waiting > 0 -> ExperimentNote(stringResource(R.string.mac_gateway_board_waiting, waiting))
            }
            ExperimentActionButton(
                text = stringResource(R.string.mac_gateway_open_board),
                enabled = active && online && board != null,
            ) { showsBoard = true }
        }
        ExperimentSectionCard(title = stringResource(R.string.mac_gateway_schedules_title)) {
            if (!online || schedules == null) {
                MacCapabilityNote(state.reachable, schedulesResult)
            } else {
                if (schedules.isEmpty()) ExperimentNote(stringResource(R.string.mac_gateway_empty_schedules))
                schedules.forEachIndexed { index, schedule ->
                    if (index > 0) ExperimentDivider(startPadding = 0.dp)
                    MacScheduleRow(
                        schedule,
                        canManage = canManage,
                        onEnabled = { enabled -> change { repository.setMacScheduleEnabled(schedule.id, enabled, generation) } },
                        onRun = { runConfirmation = schedule },
                    )
                }
                ExperimentActionButton(
                    text = stringResource(R.string.mac_gateway_new_schedule),
                    enabled = canManage,
                ) {
                    error = null
                    success = null
                    draft = MacScheduleDraft()
                }
            }
            if (draft == null) error?.let { ExperimentNote(it, error = true) }
            success?.let { ExperimentNote(stringResource(it)) }
        }
    }

    if (active && showsBoard) {
        MacBoardSheet(
            board = board.takeIf { online },
            error = approvalError,
            approvalBusy = approvalBusy,
            onDismiss = { showsBoard = false },
            onDecision = onDecision,
        )
    }
    draft?.takeIf { active }?.let { pending ->
        MacScheduleEditor(
            draft = pending,
            busy = busy,
            canSave = canManage,
            error = error,
            onChange = { draft = it },
            onDismiss = { if (!busy) draft = null },
            onSave = {
                change(R.string.mac_gateway_schedule_created) {
                    repository.createMacSchedule(pending, generation)
                    draft = null
                }
            },
        )
    }
    runConfirmation?.takeIf { active }?.let { requested ->
        val schedule = schedules?.firstOrNull { it.id == requested.id }
        AlertDialog(
            onDismissRequest = { runConfirmation = null },
            title = { Text(stringResource(R.string.mac_gateway_run_schedule_title, schedule?.name ?: requested.name)) },
            text = {
                Text(stringResource(when {
                    schedule == null -> R.string.mac_gateway_tasks_unavailable
                    schedule.autoApprovesHighRisk -> R.string.mac_gateway_run_schedule_auto_approval
                    else -> R.string.mac_gateway_run_schedule_message
                }))
            },
            confirmButton = {
                TextButton(
                    enabled = canManage && schedule?.running == false,
                    onClick = {
                        runConfirmation = null
                        change(R.string.mac_gateway_schedule_started) { repository.runMacSchedule(requested.id, generation) }
                    },
                ) { Text(stringResource(R.string.mac_gateway_run_schedule)) }
            },
            dismissButton = {
                TextButton(onClick = { runConfirmation = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun MacCapabilityNote(reachable: Boolean?, result: Result<*>?) {
    val context = LocalContext.current
    val failure = result?.exceptionOrNull()
    val text = when {
        reachable == false -> stringResource(R.string.amber_multi_device_unreachable)
        failure is MacGatewayException.NotFound -> stringResource(R.string.mac_gateway_tasks_unsupported)
        failure != null -> failure.describe(context)
        else -> stringResource(R.string.mac_gateway_connecting)
    }
    ExperimentNote(text, error = reachable == false || failure != null)
}

@Composable
private fun MacScheduleRow(
    schedule: MacGatewaySchedule,
    canManage: Boolean,
    onEnabled: (Boolean) -> Unit,
    onRun: () -> Unit,
) {
    val colors = workspaceColors()
    val nextRunAt = schedule.nextRunAt
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(schedule.name, style = MaterialTheme.typography.bodyLarge, color = colors.ink)
        Text(schedule.rule, style = MaterialTheme.typography.bodySmall, color = colors.muted)
        when {
            schedule.running -> ExperimentNote(stringResource(R.string.mac_gateway_schedule_running))
            schedule.paused -> ExperimentNote(stringResource(R.string.mac_gateway_schedules_paused))
            nextRunAt != null -> ExperimentNote(stringResource(
                R.string.mac_gateway_schedule_next, macTaskDate(nextRunAt),
            ))
        }
        schedule.lastRunAt?.let { ExperimentNote(stringResource(R.string.mac_gateway_schedule_last, macTaskDate(it))) }
        schedule.lastStatus?.let { status ->
            val label = when (status) {
                "completed", "success", "succeeded" -> R.string.mac_gateway_state_completed
                "failed" -> R.string.mac_gateway_state_abnormal
                "cancelled", "stopped" -> R.string.mac_gateway_state_stopped
                "running" -> R.string.mac_gateway_state_running
                "waiting" -> R.string.mac_gateway_state_waiting
                else -> null
            }
            if (label != null) ExperimentNote(stringResource(label))
        }
        if (schedule.autoApprovesHighRisk) ExperimentNote(stringResource(R.string.mac_gateway_schedule_auto_approval))
        ExperimentActionRow {
            ExperimentActionButton(
                text = stringResource(if (schedule.enabled) R.string.mac_gateway_schedule_disable else R.string.mac_gateway_schedule_enable),
                enabled = canManage,
                onClick = { onEnabled(!schedule.enabled) },
            )
            ExperimentActionButton(
                text = stringResource(R.string.mac_gateway_run_schedule),
                enabled = canManage && !schedule.running,
                onClick = onRun,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MacBoardSheet(
    board: List<MacBoardItem>?,
    error: String?,
    approvalBusy: Boolean,
    onDismiss: () -> Unit,
    onDecision: (MacBoardItem.Approval, Boolean) -> Unit,
) {
    var showsDone by remember { mutableStateOf(false) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = workspaceColors().paper,
    ) {
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { Text(stringResource(R.string.mac_gateway_board_title), style = MaterialTheme.typography.titleLarge) }
            error?.let { item { ExperimentNote(it, error = true) } }
            if (board == null) {
                item { ExperimentNote(stringResource(R.string.mac_gateway_tasks_unavailable)) }
                return@LazyColumn
            }
            if (board.isEmpty()) item { ExperimentNote(stringResource(R.string.mac_gateway_empty_board)) }
            MacBoardItem.Column.entries.forEach { column ->
                val rows = board.filter { it.column == column }
                if (rows.isEmpty()) return@forEach
                item(column) {
                    if (column == MacBoardItem.Column.DONE) {
                        TextButton(onClick = { showsDone = !showsDone }) {
                            Text(stringResource(
                                if (showsDone) R.string.mac_gateway_board_hide_done else R.string.mac_gateway_board_show_done,
                                rows.size,
                            ))
                        }
                    } else {
                        Text(stringResource(when (column) {
                            MacBoardItem.Column.NEEDS_YOU -> R.string.mac_gateway_board_needs_you
                            MacBoardItem.Column.RUNNING -> R.string.mac_gateway_state_running
                            MacBoardItem.Column.SCHEDULED -> R.string.mac_gateway_board_scheduled
                            MacBoardItem.Column.DONE -> R.string.mac_gateway_state_completed
                        }), style = MaterialTheme.typography.titleMedium)
                    }
                }
                if (column != MacBoardItem.Column.DONE || showsDone) {
                    items(rows, key = { it.id }) { row ->
                        ExperimentSectionCard(title = row.title) {
                            if (row.detail.isNotBlank()) ExperimentNote(row.detail)
                            val next = row.nextRunAt
                            ExperimentNote(
                                if (next != null) stringResource(R.string.mac_gateway_schedule_next, macTaskDate(next))
                                else macTaskDate(row.updatedAt),
                            )
                            row.approval?.let { approval ->
                                PendingApprovalCard(approval.asPendingApproval(), approvalBusy) { allow -> onDecision(approval, allow) }
                            }
                            if (row.approval == null && row.kind == "run" && column == MacBoardItem.Column.NEEDS_YOU) {
                                ExperimentNote(stringResource(R.string.mac_gateway_board_handle_on_mac))
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MacScheduleEditor(
    draft: MacScheduleDraft,
    busy: Boolean,
    canSave: Boolean,
    error: String?,
    onChange: (MacScheduleDraft) -> Unit,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
) {
    val context = LocalContext.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = workspaceColors().paper,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        LazyColumn(
            modifier = Modifier.imePadding(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { Text(stringResource(R.string.mac_gateway_new_schedule), style = MaterialTheme.typography.titleLarge) }
            item {
                OutlinedTextField(
                    value = draft.name,
                    onValueChange = { onChange(draft.copy(name = it)) },
                    label = { Text(stringResource(R.string.mac_gateway_name)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = !busy,
                )
            }
            item {
                OutlinedTextField(
                    value = draft.prompt,
                    onValueChange = { onChange(draft.copy(prompt = it)) },
                    label = { Text(stringResource(R.string.mac_gateway_schedule_prompt)) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    maxLines = 6,
                    enabled = !busy,
                )
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.mac_gateway_schedule_rule))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MacScheduleDraft.Rule.entries.forEach { rule ->
                            FilterChip(
                                selected = draft.rule == rule,
                                onClick = { onChange(draft.copy(rule = rule)) },
                                label = { Text(stringResource(rule.titleRes)) },
                                enabled = !busy,
                            )
                        }
                    }
                    AnimatedVisibility(draft.rule == MacScheduleDraft.Rule.INTERVAL) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            MacScheduleDraft.selectableIntervals.forEach { minutes ->
                                FilterChip(
                                    selected = draft.intervalMinutes == minutes,
                                    onClick = { onChange(draft.copy(intervalMinutes = minutes)) },
                                    label = { Text(stringResource(R.string.mac_gateway_schedule_interval_minutes, minutes)) },
                                    enabled = !busy,
                                )
                            }
                        }
                    }
                    AnimatedVisibility(draft.rule != MacScheduleDraft.Rule.INTERVAL) {
                        val time = Calendar.getInstance().apply {
                            set(Calendar.HOUR_OF_DAY, draft.hour)
                            set(Calendar.MINUTE, draft.minute)
                        }.time
                        TextButton(
                            enabled = !busy,
                            onClick = {
                                TimePickerDialog(
                                    context,
                                    { _, hour, minute -> onChange(draft.copy(hour = hour, minute = minute)) },
                                    draft.hour,
                                    draft.minute,
                                    DateFormat.is24HourFormat(context),
                                ).show()
                            },
                        ) {
                            Text(stringResource(R.string.mac_gateway_schedule_time, DateFormat.getTimeFormat(context).format(time)))
                        }
                    }
                    AnimatedVisibility(draft.rule == MacScheduleDraft.Rule.WEEKLY) {
                        val days = DateFormatSymbols().shortWeekdays
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(2, 3, 4, 5, 6, 7, 1).forEach { day ->
                                FilterChip(
                                    selected = day in draft.weekdays,
                                    onClick = {
                                        val weekdays = if (day in draft.weekdays) draft.weekdays - day else draft.weekdays + day
                                        onChange(draft.copy(weekdays = weekdays))
                                    },
                                    label = { Text(days[day]) },
                                    enabled = !busy,
                                )
                            }
                        }
                    }
                }
            }
            item { ExperimentNote(stringResource(R.string.mac_gateway_schedule_policy)) }
            if (!canSave && !busy) item { ExperimentNote(stringResource(R.string.mac_gateway_tasks_unavailable), error = true) }
            error?.let { item { ExperimentNote(it, error = true) } }
            item {
                ExperimentActionRow {
                    ExperimentActionButton(
                        text = stringResource(if (busy) R.string.mac_gateway_connecting else R.string.mac_gateway_schedule_create),
                        enabled = canSave && draft.isComplete,
                        primary = true,
                        onClick = onSave,
                    )
                    ExperimentActionButton(text = stringResource(R.string.cancel), enabled = !busy, onClick = onDismiss)
                }
            }
        }
    }
}

private val MacScheduleDraft.Rule.titleRes: Int
    get() = when (this) {
        MacScheduleDraft.Rule.DAILY -> R.string.mac_gateway_schedule_daily
        MacScheduleDraft.Rule.WEEKDAYS -> R.string.mac_gateway_schedule_weekdays
        MacScheduleDraft.Rule.WEEKLY -> R.string.mac_gateway_schedule_weekly
        MacScheduleDraft.Rule.INTERVAL -> R.string.mac_gateway_schedule_interval
    }

@Composable
private fun macTaskDate(seconds: Double): String = DateUtils.formatDateTime(
    LocalContext.current,
    (seconds * 1000).toLong(),
    DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME,
)
