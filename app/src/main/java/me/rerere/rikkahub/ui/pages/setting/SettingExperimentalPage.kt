package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ServerStack01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.agent.icloud.ICLOUD_LOGIN_URL
import me.rerere.rikkahub.data.agent.icloud.ICloudDriveManager
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.webview.WebView
import me.rerere.rikkahub.ui.components.webview.rememberWebViewState
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import org.koin.compose.koinInject

@Composable
fun SettingExperimentalPage(
    iCloudDriveManager: ICloudDriveManager = koinInject(),
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val iCloudState by iCloudDriveManager.state.collectAsStateWithLifecycle()
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    var showICloudLogin by remember { mutableStateOf(false) }
    var iCloudBusy by remember { mutableStateOf(false) }
    var iCloudVaultInput by remember(iCloudState.vaultPath) { mutableStateOf(iCloudState.vaultPath) }
    val iCloudSavedToast = stringResource(R.string.setting_icloud_saved)

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.setting_experimental_page_title)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding + PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                CardGroup(
                    title = { Text(stringResource(R.string.setting_icloud_section)) },
                ) {
                    item(
                        leadingContent = { Icon(HugeIcons.ServerStack01, contentDescription = null) },
                        headlineContent = { Text(stringResource(R.string.setting_icloud_title)) },
                        supportingContent = {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text(stringResource(R.string.setting_icloud_desc))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    Text(
                                        text = stringResource(R.string.setting_icloud_enabled),
                                        modifier = Modifier.weight(1f),
                                    )
                                    Switch(
                                        checked = iCloudState.enabled,
                                        onCheckedChange = { iCloudDriveManager.setEnabled(it) },
                                    )
                                }
                                OutlinedTextField(
                                    value = iCloudVaultInput,
                                    onValueChange = { iCloudVaultInput = it },
                                    modifier = Modifier.fillMaxWidth(),
                                    enabled = iCloudState.enabled,
                                    singleLine = true,
                                    label = { Text(stringResource(R.string.setting_icloud_vault_path)) },
                                    supportingText = { Text(stringResource(R.string.setting_icloud_vault_path_desc)) },
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Button(
                                        onClick = {
                                            iCloudDriveManager.setVaultPath(iCloudVaultInput)
                                            toaster.show(iCloudSavedToast)
                                        },
                                        enabled = iCloudState.enabled,
                                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                                    ) {
                                        Text(stringResource(R.string.setting_icloud_save_path), maxLines = 1)
                                    }
                                    OutlinedButton(
                                        onClick = { showICloudLogin = !showICloudLogin },
                                        enabled = iCloudState.enabled,
                                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                                    ) {
                                        Text(stringResource(R.string.setting_icloud_login), maxLines = 1)
                                    }
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Button(
                                        onClick = {
                                            iCloudBusy = true
                                            scope.launch {
                                                iCloudDriveManager.probe()
                                                iCloudBusy = false
                                            }
                                        },
                                        enabled = iCloudState.enabled && !iCloudBusy,
                                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                                    ) {
                                        Text(stringResource(R.string.setting_icloud_probe), maxLines = 1)
                                    }
                                    OutlinedButton(
                                        onClick = {
                                            iCloudBusy = true
                                            scope.launch {
                                                iCloudDriveManager.runWriteProbe()
                                                iCloudBusy = false
                                            }
                                        },
                                        enabled = iCloudState.enabled && !iCloudBusy,
                                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                                    ) {
                                        Text(stringResource(R.string.setting_icloud_write_probe), maxLines = 1)
                                    }
                                }
                                Text(
                                    text = stringResource(
                                        R.string.setting_icloud_status,
                                        iCloudState.status.wireName,
                                        iCloudState.capability.wireName,
                                    ),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                iCloudState.message?.takeIf { it.isNotBlank() }?.let { message ->
                                    Text(
                                        text = message,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                if (showICloudLogin && iCloudState.enabled) {
                                    ICloudLoginWebView()
                                }
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ICloudLoginWebView() {
    val state = rememberWebViewState(
        url = ICLOUD_LOGIN_URL,
        settings = {
            domStorageEnabled = true
            builtInZoomControls = true
            displayZoomControls = false
            userAgentString = ICLOUD_WEBVIEW_USER_AGENT
        },
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(420.dp)
            .clip(MaterialTheme.shapes.medium),
    ) {
        WebView(
            state = state,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

private const val ICLOUD_WEBVIEW_USER_AGENT =
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.3.1 Safari/605.1.15"
