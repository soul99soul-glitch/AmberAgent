package me.rerere.rikkahub.ui.pages.setting

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import me.rerere.rikkahub.ui.components.ui.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.File02
import me.rerere.rikkahub.R
import app.amber.feature.office.FeishuOfficeEnhancementManager
import app.amber.feature.office.FeishuWorkProject
import app.amber.feature.office.radar.DocRadar
import app.amber.feature.workspace.WorkspaceManager
import me.rerere.rikkahub.data.db.dao.DocChangeLogDAO
import me.rerere.rikkahub.data.db.dao.DocSubscriptionDAO
import me.rerere.rikkahub.data.db.entity.DocSubscriptionEntity
import me.rerere.rikkahub.ui.components.ui.workspaceColors
import me.rerere.rikkahub.ui.context.LocalToaster
import app.amber.core.utils.plus
import org.koin.compose.koinInject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SettingExperimentalOfficeProPage(
    feishuOfficeManager: FeishuOfficeEnhancementManager = koinInject(),
    docRadar: DocRadar = koinInject(),
    docSubscriptionDao: DocSubscriptionDAO = koinInject(),
    docChangeLogDao: DocChangeLogDAO = koinInject(),
) {
    val officeState by feishuOfficeManager.state.collectAsStateWithLifecycle()
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    var officeBusy by remember { mutableStateOf(false) }
    var officePackageInput by remember(officeState.targetPackage) { mutableStateOf(officeState.targetPackage) }
    var officeCandidates by remember { mutableStateOf("") }
    val officeSavedToast = stringResource(R.string.setting_officepro_saved)
    val officeDetectNoneToast = stringResource(R.string.setting_officepro_detect_none)
    var docRadarNotify by remember { mutableStateOf(true) }
    var subscriptions by remember { mutableStateOf<List<DocSubscriptionEntity>>(emptyList()) }
    var showWatchDialog by remember { mutableStateOf(false) }
    var checkBusy by remember { mutableStateOf(false) }

    LaunchedEffect(officeState.enabled) {
        if (officeState.enabled) {
            subscriptions = docSubscriptionDao.getEnabled()
        }
    }

    ExperimentalSettingsScaffold(
        title = stringResource(R.string.setting_officepro_title),
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding + PaddingValues(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                ExperimentHeroCard(
                    icon = { Icon(HugeIcons.File02, contentDescription = null) },
                    title = stringResource(R.string.setting_officepro_title),
                    description = stringResource(R.string.setting_officepro_desc),
                    trailing = {
                        Switch(
                            checked = officeState.enabled,
                            onCheckedChange = { feishuOfficeManager.setEnabled(it) },
                        )
                    },
                )
            }
            item {
                ExperimentSectionCard(title = stringResource(R.string.setting_officepro_connection_section)) {
                    OutlinedTextField(
                        value = officePackageInput,
                        onValueChange = { officePackageInput = it },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = officeState.enabled,
                        singleLine = true,
                        label = { Text(stringResource(R.string.setting_officepro_package)) },
                        supportingText = { Text(stringResource(R.string.setting_officepro_package_desc)) },
                    )
                    ExperimentActionRow {
                        ExperimentActionButton(
                            text = stringResource(R.string.setting_officepro_save_package),
                            primary = true,
                            enabled = officeState.enabled,
                            onClick = {
                                feishuOfficeManager.setTargetPackage(officePackageInput)
                                toaster.show(officeSavedToast)
                            },
                        )
                        ExperimentActionButton(
                            text = stringResource(R.string.setting_officepro_detect),
                            enabled = officeState.enabled && !officeBusy,
                            onClick = {
                                officeBusy = true
                                scope.launch {
                                    val candidates = feishuOfficeManager.detectPackages()
                                    val selected = feishuOfficeManager.chooseAndSaveBestPackage(candidates)
                                    officeCandidates = candidates.joinToString("\n") {
                                        "${it.label} · ${it.packageName}"
                                    }
                                    if (selected == null) {
                                        toaster.show(officeDetectNoneToast)
                                    } else {
                                        officePackageInput = selected.packageName
                                        toaster.show(officeSavedToast)
                                    }
                                    feishuOfficeManager.refresh()
                                    officeBusy = false
                                }
                            },
                        )
                        ExperimentActionButton(
                            text = stringResource(R.string.setting_officepro_open),
                            enabled = officeState.enabled && officeState.launchable,
                            onClick = { feishuOfficeManager.openTargetApp() },
                        )
                        ExperimentActionButton(
                            text = stringResource(R.string.setting_officepro_refresh),
                            enabled = officeState.enabled,
                            onClick = { feishuOfficeManager.refresh() },
                        )
                    }
                    ExperimentDivider()
                    ExperimentStatusRow(stringResource(R.string.setting_experimental_capability), officeState.capability.wireName)
                    ExperimentStatusRow(stringResource(R.string.setting_experimental_package), officeState.targetPackage)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        ExperimentBooleanPill(stringResource(R.string.setting_experimental_installed), officeState.installed)
                        ExperimentBooleanPill(stringResource(R.string.setting_experimental_accessibility), officeState.accessibilityReady)
                        ExperimentBooleanPill(stringResource(R.string.setting_experimental_notifications), officeState.notificationReady)
                        ExperimentBooleanPill(stringResource(R.string.setting_experimental_usage), officeState.usageReady)
                    }
                    ExperimentNote(text = stringResource(R.string.setting_officepro_mcp_note))
                    officeState.lastError?.takeIf { it.isNotBlank() }?.let { error ->
                        ExperimentNote(text = error, error = true)
                    }
                    officeCandidates.takeIf { it.isNotBlank() }?.let { candidates ->
                        ExperimentNote(
                            text = stringResource(R.string.setting_officepro_candidates, candidates),
                        )
                    }
                }
            }
            // Simplified: 2-toggles instead of the old 5-switch detail panel
            item {
                ExperimentSectionCard(title = stringResource(R.string.setting_officepro_capture_section)) {
                    OfficeProSwitchRow(
                        text = stringResource(R.string.setting_officepro_local_signals),
                        checked = officeState.includeNotificationsByDefault || officeState.includeUsageByDefault,
                        enabled = officeState.enabled,
                        onCheckedChange = { checked ->
                            feishuOfficeManager.setIncludeNotificationsByDefault(checked)
                            feishuOfficeManager.setIncludeUsageByDefault(checked)
                        },
                    )
                    ExperimentNote(text = stringResource(R.string.setting_officepro_local_signals_note))
                    ExperimentDivider()
                    OfficeProSwitchRow(
                        text = stringResource(R.string.setting_officepro_mcp_enhance),
                        checked = officeState.includeMcpHintsByDefault,
                        enabled = officeState.enabled,
                        onCheckedChange = feishuOfficeManager::setIncludeMcpHintsByDefault,
                    )
                    ExperimentNote(text = stringResource(R.string.setting_officepro_mcp_note))
                }
            }
            // New: Document Radar
            item {
                ExperimentSectionCard(title = stringResource(R.string.setting_officepro_doc_radar_section)) {
                    ExperimentNote(text = stringResource(R.string.setting_officepro_doc_radar_desc))
                    ExperimentActionRow {
                        ExperimentActionButton(
                            text = stringResource(R.string.setting_officepro_watch_doc),
                            primary = true,
                            enabled = officeState.enabled,
                            onClick = { showWatchDialog = true },
                        )
                        ExperimentActionButton(
                            text = stringResource(R.string.setting_officepro_check_now),
                            enabled = officeState.enabled && !checkBusy,
                            onClick = {
                                checkBusy = true
                                scope.launch {
                                    try {
                                        docRadar.runOnce()
                                        subscriptions = docSubscriptionDao.getEnabled()
                                        toaster.show(officeSavedToast)
                                    } catch (e: Exception) {
                                        toaster.show(e.message ?: "检查失败")
                                    }
                                    checkBusy = false
                                }
                            },
                        )
                    }
                    if (subscriptions.isNotEmpty()) {
                        subscriptions.forEach { doc ->
                            ExperimentDivider()
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = doc.docTitle,
                                        modifier = Modifier.weight(1f),
                                        style = MaterialTheme.typography.titleSmall,
                                    )
                                    ExperimentActionButton(
                                        text = stringResource(R.string.setting_officepro_doc_unsubscribe),
                                        enabled = officeState.enabled,
                                        onClick = {
                                            scope.launch {
                                                docRadar.unsubscribe(doc.id)
                                                subscriptions = docSubscriptionDao.getEnabled()
                                            }
                                        },
                                    )
                                }
                                if (!doc.hasBaseline) {
                                    Text(
                                        text = "⚠ 基线未建立",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = workspaceColors().faint,
                                    )
                                }
                                doc.lastCheckedAt?.let { lastCheckedAt ->
                                    Text(
                                        text = stringResource(
                                            R.string.setting_officepro_doc_last_checked,
                                            formatOfficeProjectUpdatedAt(lastCheckedAt),
                                        ),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = workspaceColors().faint,
                                    )
                                }
                                doc.lastChangedAt?.let { lastChangedAt ->
                                    Text(
                                        text = stringResource(
                                            R.string.setting_officepro_doc_last_changed,
                                            formatOfficeProjectUpdatedAt(lastChangedAt),
                                            doc.threshold,
                                        ),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = workspaceColors().faint,
                                    )
                                } ?: run {
                                    Text(
                                        text = stringResource(R.string.setting_officepro_doc_no_change),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = workspaceColors().faint,
                                    )
                                }
                            }
                        }
                    } else {
                        ExperimentNote(text = stringResource(R.string.setting_officepro_doc_empty))
                    }
                    ExperimentDivider()
                    ExperimentStatusRow(
                        label = stringResource(R.string.setting_officepro_check_interval_label),
                        value = stringResource(R.string.setting_officepro_interval_value),
                    )
                    OfficeProSwitchRow(
                        text = stringResource(R.string.setting_officepro_change_notification),
                        checked = docRadarNotify,
                        enabled = officeState.enabled,
                        onCheckedChange = { docRadarNotify = it },
                    )
                }
            }
        }
    }
    if (showWatchDialog) {
        WatchDocDialog(
            onDismiss = { showWatchDialog = false },
            onSave = { title, url, threshold, interval, notify ->
                scope.launch {
                    val error = docRadar.subscribe(
                        url = url,
                        title = title,
                        threshold = threshold,
                        notifyEnabled = notify,
                    )
                    if (error != null) {
                        toaster.show(error)
                    } else {
                        toaster.show(officeSavedToast)
                    }
                    subscriptions = docSubscriptionDao.getEnabled()
                    showWatchDialog = false
                }
            },
            onToast = toaster::show,
        )
    }
}

@Composable
private fun OfficeProjectEditorDialog(
    project: FeishuWorkProject?,
    workspaceManager: WorkspaceManager,
    context: Context,
    onDismiss: () -> Unit,
    onSave: (FeishuWorkProject) -> Unit,
    onToast: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var nameInput by remember(project?.id) { mutableStateOf(project?.name.orEmpty()) }
    var keywordsInput by remember(project?.id) { mutableStateOf(project?.keywords.orEmpty().joinToString("\n")) }
    var currentGoalInput by remember(project?.id) { mutableStateOf(project?.currentGoal.orEmpty()) }
    var coreSellingPointsInput by remember(project?.id) { mutableStateOf(project?.coreSellingPoints.orEmpty().joinToString("\n")) }
    var risksInput by remember(project?.id) { mutableStateOf(project?.risks.orEmpty().joinToString("\n")) }
    var openQuestionsInput by remember(project?.id) { mutableStateOf(project?.openQuestions.orEmpty().joinToString("\n")) }
    var keyDecisionsInput by remember(project?.id) { mutableStateOf(project?.keyDecisions.orEmpty().joinToString("\n")) }
    var recentChangesInput by remember(project?.id) { mutableStateOf(project?.recentChanges.orEmpty().joinToString("\n")) }
    var sourceRefsInput by remember(project?.id) { mutableStateOf(project?.sourceRefs.orEmpty().joinToString("\n")) }
    var importing by remember { mutableStateOf(false) }
    val importSuccess = stringResource(R.string.setting_officepro_import_source_success)
    val blankNameError = stringResource(R.string.setting_officepro_project_name_required)
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        importing = true
        scope.launch {
            runCatching {
                importOfficeProjectSource(
                    context = context,
                    workspaceManager = workspaceManager,
                    uri = uri,
                    projectName = nameInput.ifBlank { project?.name ?: "officepro-project" },
                )
            }.onSuccess { path ->
                sourceRefsInput = appendSourceRef(sourceRefsInput, path)
                onToast(importSuccess.format(path))
            }.onFailure { error ->
                onToast(error.message ?: error.toString())
            }
            importing = false
        }
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.92f)
                .padding(16.dp),
            shape = RoundedCornerShape(16.dp),
            color = workspaceColors().paper,
            border = BorderStroke(1.dp, workspaceColors().hairline),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.setting_officepro_project_editor_title),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium,
                        color = workspaceColors().ink,
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(HugeIcons.Cancel01, contentDescription = null)
                    }
                }
                ExperimentNote(text = stringResource(R.string.setting_officepro_project_editor_desc))
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    item {
                        OutlinedTextField(
                            value = nameInput,
                            onValueChange = { nameInput = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(stringResource(R.string.setting_officepro_project_name)) },
                            singleLine = true,
                        )
                    }
                    item {
                        OutlinedTextField(
                            value = keywordsInput,
                            onValueChange = { keywordsInput = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(stringResource(R.string.setting_officepro_project_keywords)) },
                            supportingText = { Text(stringResource(R.string.setting_officepro_project_list_field_desc)) },
                            minLines = 2,
                            maxLines = 5,
                        )
                    }
                    item {
                        OutlinedTextField(
                            value = currentGoalInput,
                            onValueChange = { currentGoalInput = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(stringResource(R.string.setting_officepro_project_goal)) },
                            minLines = 2,
                            maxLines = 5,
                        )
                    }
                    item {
                        OutlinedTextField(
                            value = sourceRefsInput,
                            onValueChange = { sourceRefsInput = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(stringResource(R.string.setting_officepro_project_sources)) },
                            supportingText = { Text(stringResource(R.string.setting_officepro_project_sources_desc)) },
                            minLines = 3,
                            maxLines = 7,
                        )
                    }
                    item {
                        ExperimentActionRow {
                            ExperimentActionButton(
                                text = stringResource(R.string.setting_officepro_import_source),
                                enabled = !importing,
                                onClick = { importLauncher.launch(arrayOf("*/*")) },
                            )
                        }
                    }
                    item {
                        OutlinedTextField(
                            value = coreSellingPointsInput,
                            onValueChange = { coreSellingPointsInput = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(stringResource(R.string.setting_officepro_project_selling_points)) },
                            supportingText = { Text(stringResource(R.string.setting_officepro_project_list_field_desc)) },
                            minLines = 2,
                            maxLines = 5,
                        )
                    }
                    item {
                        OutlinedTextField(
                            value = risksInput,
                            onValueChange = { risksInput = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(stringResource(R.string.setting_officepro_project_risks)) },
                            supportingText = { Text(stringResource(R.string.setting_officepro_project_list_field_desc)) },
                            minLines = 2,
                            maxLines = 5,
                        )
                    }
                    item {
                        OutlinedTextField(
                            value = openQuestionsInput,
                            onValueChange = { openQuestionsInput = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(stringResource(R.string.setting_officepro_project_open_questions)) },
                            supportingText = { Text(stringResource(R.string.setting_officepro_project_list_field_desc)) },
                            minLines = 2,
                            maxLines = 5,
                        )
                    }
                    item {
                        OutlinedTextField(
                            value = keyDecisionsInput,
                            onValueChange = { keyDecisionsInput = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(stringResource(R.string.setting_officepro_project_key_decisions)) },
                            supportingText = { Text(stringResource(R.string.setting_officepro_project_list_field_desc)) },
                            minLines = 2,
                            maxLines = 5,
                        )
                    }
                    item {
                        OutlinedTextField(
                            value = recentChangesInput,
                            onValueChange = { recentChangesInput = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(stringResource(R.string.setting_officepro_project_recent_changes)) },
                            supportingText = { Text(stringResource(R.string.setting_officepro_project_list_field_desc)) },
                            minLines = 2,
                            maxLines = 5,
                        )
                    }
                }
                ExperimentActionRow {
                    ExperimentActionButton(
                        text = stringResource(R.string.setting_officepro_save_project),
                        primary = true,
                        enabled = !importing,
                        onClick = {
                            val cleanName = nameInput.trim()
                            if (cleanName.isBlank()) {
                                onToast(blankNameError)
                            } else {
                                onSave(
                                    FeishuWorkProject(
                                        id = project?.id ?: cleanName.toOfficeProjectId(),
                                        name = cleanName.take(80),
                                        keywords = parseProjectList(keywordsInput).ifEmpty { listOf(cleanName.take(80)) },
                                        currentGoal = currentGoalInput.trim().take(500),
                                        coreSellingPoints = parseProjectList(coreSellingPointsInput),
                                        risks = parseProjectList(risksInput),
                                        openQuestions = parseProjectList(openQuestionsInput),
                                        keyDecisions = parseProjectList(keyDecisionsInput),
                                        recentChanges = parseProjectList(recentChangesInput),
                                        sourceRefs = parseProjectSourceRefs(sourceRefsInput),
                                        updatedAtMs = System.currentTimeMillis(),
                                    )
                                )
                            }
                        },
                    )
                    ExperimentActionButton(
                        text = stringResource(R.string.cancel),
                        enabled = !importing,
                        onClick = onDismiss,
                    )
                }
            }
        }
    }
}

@Composable
private fun WatchDocDialog(
    onDismiss: () -> Unit,
    onSave: (title: String, url: String, threshold: Int, interval: Int, notify: Boolean) -> Unit,
    onToast: (String) -> Unit,
) {
    var titleInput by remember { mutableStateOf("") }
    var urlInput by remember { mutableStateOf("") }
    var thresholdInput by remember { mutableStateOf("500") }
    var intervalInput by remember { mutableStateOf("90") }
    var notifyEnabled by remember { mutableStateOf(true) }
    val workspace = workspaceColors()
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(16.dp),
            color = workspace.paper,
            border = BorderStroke(1.dp, workspace.hairline),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.setting_officepro_watch_doc),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(HugeIcons.Cancel01, contentDescription = null)
                    }
                }
                OutlinedTextField(
                    value = urlInput,
                    onValueChange = { urlInput = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("飞书文档 URL") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = titleInput,
                    onValueChange = { titleInput = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("文档标题") },
                    singleLine = true,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedTextField(
                        value = thresholdInput,
                        onValueChange = { thresholdInput = it },
                        modifier = Modifier.weight(1f),
                        label = { Text("变更阈值（字）") },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = intervalInput,
                        onValueChange = { intervalInput = it },
                        modifier = Modifier.weight(1f),
                        label = { Text("间隔（分钟）") },
                        singleLine = true,
                    )
                }
                OfficeProSwitchRow(
                    text = stringResource(R.string.setting_officepro_change_notification),
                    checked = notifyEnabled,
                    enabled = true,
                    onCheckedChange = { notifyEnabled = it },
                )
                ExperimentActionRow {
                    ExperimentActionButton(
                        text = stringResource(R.string.setting_officepro_save_project),
                        primary = true,
                        enabled = urlInput.isNotBlank() && titleInput.isNotBlank(),
                        onClick = {
                            val url = urlInput.trim()
                            val title = titleInput.trim()
                            val threshold = thresholdInput.trim().toIntOrNull() ?: 500
                            val interval = intervalInput.trim().toIntOrNull() ?: 90
                            if (url.isBlank() || title.isBlank()) {
                                onToast("请填写文档 URL 和标题")
                            } else {
                                onSave(title, url, threshold, interval, notifyEnabled)
                            }
                        },
                    )
                    ExperimentActionButton(
                        text = stringResource(R.string.cancel),
                        enabled = true,
                        onClick = onDismiss,
                    )
                }
            }
        }
    }
}

@Composable
private fun OfficeProSwitchRow(
    text: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val workspace = workspaceColors()
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = if (enabled) workspace.ink else workspace.faint,
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
        )
    }
}

private const val OFFICE_PROJECT_IMPORT_MAX_BYTES = 50L * 1024L * 1024L

private fun parseProjectList(raw: String): List<String> =
    raw.split('\n', ',', '，', ';', '；')
        .map { it.trim().take(500) }
        .filter { it.isNotBlank() }
        .distinct()
        .take(24)

private fun parseProjectSourceRefs(raw: String): List<String> =
    raw.lineSequence()
        .flatMap { it.split('，', ';', '；').asSequence() }
        .map { it.trim().take(500) }
        .filter { it.isNotBlank() }
        .distinct()
        .take(32)
        .toList()

private fun appendSourceRef(existing: String, path: String): String =
    (parseProjectSourceRefs(existing) + path)
        .distinct()
        .joinToString("\n")

private suspend fun importOfficeProjectSource(
    context: Context,
    workspaceManager: WorkspaceManager,
    uri: Uri,
    projectName: String,
): String = withContext(Dispatchers.IO) {
    val resolver = context.contentResolver
    var displayName: String? = null
    var sizeBytes: Long? = null
    resolver.query(uri, null, null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (nameIndex >= 0) {
                displayName = cursor.getString(nameIndex)
            }
            if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                sizeBytes = cursor.getLong(sizeIndex)
            }
        }
    }
    val knownSize = sizeBytes
    require(knownSize == null || knownSize <= OFFICE_PROJECT_IMPORT_MAX_BYTES) {
        "文件超过 50MB，先放入 workspace 后再把路径写入来源引用。"
    }
    val safeName = (displayName ?: "source-${System.currentTimeMillis()}")
        .toSafeWorkspaceFileName()
    val bytes = resolver.openInputStream(uri)?.use { input ->
        input.readBytes()
    } ?: error("无法读取选择的文件")
    require(bytes.size.toLong() <= OFFICE_PROJECT_IMPORT_MAX_BYTES) {
        "文件超过 50MB，先放入 workspace 后再把路径写入来源引用。"
    }
    val mimeType = resolver.getType(uri) ?: "application/octet-stream"
    val projectDir = projectName.toOfficeProjectId()
    val relativePath = "officepro/knowledge/$projectDir/$safeName"
    workspaceManager.writeBytes(relativePath, bytes, mimeType).path
}

private fun String.toOfficeProjectId(): String {
    val ascii = lowercase()
        .map { char ->
            when {
                char in 'a'..'z' || char in '0'..'9' -> char
                char.isWhitespace() || char in listOf('-', '_', '.', '/', '，', ',', '；', ';') -> '-'
                else -> '-'
            }
        }
        .joinToString("")
        .trim('-')
        .replace(Regex("-+"), "-")
    return ascii.ifBlank { "custom-project" }.take(64)
}

private fun String.toSafeWorkspaceFileName(): String =
    replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]"), "_")
        .trim()
        .take(120)
        .ifBlank { "source-${System.currentTimeMillis()}" }

private fun formatOfficeProjectUpdatedAt(updatedAtMs: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(updatedAtMs))
