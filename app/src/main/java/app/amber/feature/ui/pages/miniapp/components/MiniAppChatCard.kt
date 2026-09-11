package app.amber.feature.ui.pages.miniapp.components

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import app.amber.agent.R
import app.amber.ai.ui.UIMessagePart
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.AlarmClock
import com.composables.icons.lucide.CodeXml
import com.composables.icons.lucide.Download
import com.composables.icons.lucide.List
import com.composables.icons.lucide.EllipsisVertical
import app.amber.feature.miniapp.MiniAppRepository
import app.amber.core.settings.prefs.SettingsAggregator
import app.amber.agent.data.db.entity.MiniAppEntity
import app.amber.feature.ui.components.ds.AmberCard
import app.amber.feature.ui.components.ui.WorkspaceIconButton
import app.amber.feature.ui.components.ui.workspaceColors
import app.amber.feature.ui.theme.LocalAmberTokens
import app.amber.feature.ui.pages.miniapp.MiniAppSourceEditorDialog
import app.amber.feature.ui.pages.miniapp.MiniAppVersionHistoryDialog
import app.amber.feature.ui.pages.miniapp.rememberMiniAppHtmlExporter
import org.koin.compose.koinInject

@Composable
fun MiniAppChatCard(
    part: UIMessagePart.MiniApp,
    onRun: () -> Unit,
    onOpenList: () -> Unit,
    onModify: (String) -> Boolean,
    modifier: Modifier = Modifier,
    repository: MiniAppRepository = koinInject(),
    settingsStore: SettingsAggregator = koinInject(),
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val appSettings by settingsStore.settingsFlow.collectAsStateWithLifecycle()
    val showSourceButton = appSettings.agentRuntime.miniApp.showSourceButton
    val exportMiniApp = rememberMiniAppHtmlExporter()
    var menuExpanded by remember { mutableStateOf(false) }
    var sourceTarget by remember { mutableStateOf<MiniAppEntity?>(null) }
    var versionTarget by remember { mutableStateOf<MiniAppEntity?>(null) }
    var modifyTarget by remember { mutableStateOf<MiniAppEntity?>(null) }
    var modifyRequest by remember { mutableStateOf("") }

    fun withCurrentApp(action: (MiniAppEntity) -> Unit) {
        scope.launch {
            val app = repository.getById(part.appId)
            if (app == null) {
                Toast.makeText(context, R.string.miniapp_not_found, Toast.LENGTH_SHORT).show()
            } else {
                action(app)
            }
        }
    }

    val workspace = workspaceColors()
    val tokens = LocalAmberTokens.current
    AmberCard(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    modifier = Modifier.size(32.dp),
                    shape = RoundedCornerShape(9.dp),
                    color = tokens.surface2,
                    contentColor = tokens.ink2,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(part.iconEmoji ?: "▣", style = MaterialTheme.typography.titleSmall)
                    }
                }
                Text(
                    text = part.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = workspace.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
            Text(
                text = part.description,
                style = MaterialTheme.typography.bodySmall,
                color = workspace.muted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(onClick = onRun) {
                    Text(stringResource(R.string.miniapp_run))
                }
                TextButton(
                    onClick = {
                        withCurrentApp {
                            modifyTarget = it
                            modifyRequest = ""
                        }
                    },
                ) {
                    Text(stringResource(R.string.miniapp_modify))
                }
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                    WorkspaceIconButton(
                        onClick = { menuExpanded = true },
                        icon = Lucide.EllipsisVertical,
                        contentDescription = stringResource(R.string.miniapp_more_actions),
                        showBorder = false,
                        containerColor = workspace.paper,
                    )
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                    ) {
                        if (showSourceButton) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.miniapp_view_source)) },
                                leadingIcon = { Icon(Lucide.CodeXml, contentDescription = null) },
                                onClick = {
                                    menuExpanded = false
                                    withCurrentApp { sourceTarget = it }
                                },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.miniapp_open_list)) },
                            leadingIcon = { Icon(Lucide.List, contentDescription = null) },
                            onClick = {
                                menuExpanded = false
                                onOpenList()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.miniapp_export_html)) },
                            leadingIcon = { Icon(Lucide.Download, contentDescription = null) },
                            onClick = {
                                menuExpanded = false
                                withCurrentApp(exportMiniApp)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.miniapp_version_history)) },
                            leadingIcon = { Icon(Lucide.AlarmClock, contentDescription = null) },
                            onClick = {
                                menuExpanded = false
                                withCurrentApp { versionTarget = it }
                            },
                        )
                    }
                }
            }
        }
    }

    sourceTarget?.let { target ->
        MiniAppSourceEditorDialog(
            app = target,
            onDismiss = { sourceTarget = null },
        )
    }

    versionTarget?.let { target ->
        val versions by repository.observeVersions(target.id).collectAsStateWithLifecycle(initialValue = emptyList())
        MiniAppVersionHistoryDialog(
            app = target,
            versions = versions,
            onDismiss = { versionTarget = null },
            onRestore = { version ->
                scope.launch {
                    runCatching {
                        repository.restoreVersion(target.id, version.versionNumber)
                    }.onSuccess { restored ->
                        if (restored == null) {
                            Toast.makeText(context, R.string.miniapp_restore_not_found, Toast.LENGTH_SHORT).show()
                        }
                    }.onFailure {
                        val reason = it.message ?: context.getString(R.string.miniapp_unknown_error)
                        Toast.makeText(
                            context,
                            context.getString(R.string.miniapp_restore_failed, reason),
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                    versionTarget = null
                }
            },
        )
    }

    modifyTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { modifyTarget = null },
            shape = RoundedCornerShape(14.dp),
            containerColor = workspace.paper,
            title = { Text(stringResource(R.string.miniapp_modify_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = stringResource(R.string.miniapp_modify_description, target.title, target.version),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = modifyRequest,
                        onValueChange = { modifyRequest = it },
                        placeholder = { Text(stringResource(R.string.miniapp_modify_placeholder)) },
                        minLines = 3,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = modifyRequest.isNotBlank(),
                    onClick = {
                        if (onModify(target.toRevisionPrompt(modifyRequest))) {
                            modifyTarget = null
                            modifyRequest = ""
                        }
                    },
                ) {
                    Text(stringResource(R.string.miniapp_send_modification))
                }
            },
            dismissButton = {
                TextButton(onClick = { modifyTarget = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

private fun MiniAppEntity.toRevisionPrompt(request: String): String = """
    修改小应用
    appId: $id
    currentVersion: $version
    title: $title

    用户修改意见：
    ${request.trim()}

    请基于这个已保存小应用生成新版，并保留适合的能力声明。
""".trimIndent()
