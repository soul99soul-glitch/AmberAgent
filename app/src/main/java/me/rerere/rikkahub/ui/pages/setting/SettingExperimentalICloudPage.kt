package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import me.rerere.rikkahub.ui.components.ui.Switch
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.ServerStack01
import me.rerere.rikkahub.R
import app.amber.feature.icloud.ICLOUD_CHINA_LOGIN_URL
import app.amber.feature.icloud.ICLOUD_GLOBAL_LOGIN_URL
import app.amber.feature.icloud.ICloudDriveManager
import me.rerere.rikkahub.ui.components.webview.WebView
import me.rerere.rikkahub.ui.components.webview.rememberWebViewState
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.utils.plus
import org.koin.compose.koinInject

@Composable
fun SettingExperimentalICloudPage(
    iCloudDriveManager: ICloudDriveManager = koinInject(),
) {
    val iCloudState by iCloudDriveManager.state.collectAsStateWithLifecycle()
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    var showICloudLogin by remember { mutableStateOf(false) }
    var iCloudLoginUrl by remember { mutableStateOf(ICLOUD_GLOBAL_LOGIN_URL) }
    var iCloudBusy by remember { mutableStateOf(false) }
    var iCloudVaultInput by remember(iCloudState.vaultPath) { mutableStateOf(iCloudState.vaultPath) }
    val iCloudSavedToast = stringResource(R.string.setting_icloud_saved)
    val iCloudLoginSnapshot = remember(iCloudState.updatedAtMillis, iCloudBusy, showICloudLogin) {
        iCloudDriveManager.loginSnapshot()
    }

    ExperimentalSettingsScaffold(
        title = stringResource(R.string.setting_icloud_title),
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding + PaddingValues(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                ExperimentHeroCard(
                    icon = { Icon(HugeIcons.ServerStack01, contentDescription = null) },
                    title = stringResource(R.string.setting_icloud_title),
                    description = stringResource(R.string.setting_icloud_desc),
                    trailing = {
                        Switch(
                            checked = iCloudState.enabled,
                            onCheckedChange = { iCloudDriveManager.setEnabled(it) },
                        )
                    },
                )
            }
            item {
                ExperimentSectionCard(
                    title = stringResource(R.string.setting_icloud_vault_path),
                ) {
                    OutlinedTextField(
                        value = iCloudVaultInput,
                        onValueChange = { iCloudVaultInput = it },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = iCloudState.enabled,
                        singleLine = true,
                        label = { Text(stringResource(R.string.setting_icloud_vault_path)) },
                        supportingText = { Text(stringResource(R.string.setting_icloud_vault_path_desc)) },
                    )
                    ExperimentActionRow {
                        ExperimentActionButton(
                            text = stringResource(R.string.setting_icloud_save_path),
                            primary = true,
                            enabled = iCloudState.enabled && !iCloudBusy,
                            onClick = {
                                iCloudBusy = true
                                scope.launch {
                                    runCatching {
                                        iCloudDriveManager.setVaultPath(iCloudVaultInput)
                                        toaster.show(iCloudSavedToast)
                                        iCloudDriveManager.probe()
                                    }.onFailure { error ->
                                        toaster.show(error.message ?: error.toString())
                                    }
                                    iCloudBusy = false
                                }
                            },
                        )
                        ExperimentActionButton(
                            text = stringResource(R.string.setting_icloud_login),
                            enabled = iCloudState.enabled,
                            onClick = {
                                iCloudLoginUrl = ICLOUD_GLOBAL_LOGIN_URL
                                showICloudLogin = true
                            },
                        )
                        ExperimentActionButton(
                            text = stringResource(R.string.setting_icloud_login_china),
                            enabled = iCloudState.enabled,
                            onClick = {
                                iCloudLoginUrl = ICLOUD_CHINA_LOGIN_URL
                                showICloudLogin = true
                            },
                        )
                        ExperimentActionButton(
                            text = stringResource(R.string.setting_icloud_probe),
                            enabled = iCloudState.enabled && !iCloudBusy,
                            onClick = {
                                iCloudBusy = true
                                scope.launch {
                                    iCloudDriveManager.probe()
                                    iCloudBusy = false
                                }
                            },
                        )
                        ExperimentActionButton(
                            text = stringResource(R.string.setting_icloud_write_probe),
                            enabled = iCloudState.enabled && !iCloudBusy,
                            onClick = {
                                iCloudBusy = true
                                scope.launch {
                                    iCloudDriveManager.runWriteProbe()
                                    iCloudBusy = false
                                }
                            },
                        )
                    }
                }
            }
            item {
                ExperimentSectionCard(title = stringResource(R.string.setting_experimental_status_section)) {
                    ExperimentStatusRow(
                        label = stringResource(R.string.setting_experimental_status),
                        value = iCloudState.status.wireName,
                    )
                    ExperimentStatusRow(
                        label = stringResource(R.string.setting_experimental_capability),
                        value = iCloudState.capability.wireName,
                    )
                    ExperimentStatusRow(
                        label = stringResource(R.string.setting_icloud_endpoint_hint),
                        value = iCloudLoginSnapshot.endpointHint?.displayName ?: stringResource(R.string.setting_icloud_endpoint_unknown),
                    )
                    ExperimentStatusRow(
                        label = stringResource(R.string.setting_icloud_login_detected),
                        value = if (iCloudLoginSnapshot.loginDetected) {
                            stringResource(R.string.setting_icloud_login_detected_yes)
                        } else {
                            stringResource(R.string.setting_icloud_login_detected_no)
                        },
                    )
                    ExperimentStatusRow(
                        label = stringResource(R.string.setting_icloud_next_action),
                        value = iCloudDriveManager.nextAction(iCloudState),
                    )
                    iCloudState.message?.takeIf { it.isNotBlank() }?.let { message ->
                        ExperimentNote(text = message)
                    }
                }
            }
        }
    }
    if (showICloudLogin && iCloudState.enabled) {
        ICloudLoginDialog(
            url = iCloudLoginUrl,
            onDismiss = {
                showICloudLogin = false
                iCloudBusy = true
                scope.launch {
                    iCloudDriveManager.probe()
                    iCloudBusy = false
                }
            },
        )
    }
}

@Composable
private fun ICloudLoginDialog(
    url: String,
    onDismiss: () -> Unit,
) {
    val state = rememberWebViewState(
        url = url,
        settings = {
            domStorageEnabled = true
            builtInZoomControls = true
            displayZoomControls = false
            userAgentString = ICLOUD_WEBVIEW_USER_AGENT
        },
    )
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.9f),
                shape = MaterialTheme.shapes.extraLarge,
                tonalElevation = 6.dp,
                shadowElevation = 12.dp,
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 20.dp, top = 12.dp, end = 12.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.setting_icloud_login),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = onDismiss) {
                            Icon(
                                imageVector = HugeIcons.Cancel01,
                                contentDescription = stringResource(R.string.update_card_close),
                                modifier = Modifier.size(22.dp),
                            )
                        }
                    }
                    WebView(
                        state = state,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                    )
                }
            }
        }
    }
}

private const val ICLOUD_WEBVIEW_USER_AGENT =
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.3.1 Safari/605.1.15"
