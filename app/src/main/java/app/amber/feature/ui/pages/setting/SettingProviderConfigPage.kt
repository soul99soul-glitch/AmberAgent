package app.amber.feature.ui.pages.setting

import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Refresh03
import me.rerere.hugeicons.stroke.Delete01
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.amber.ai.provider.ProviderSetting
import app.amber.agent.R
import app.amber.feature.ui.components.ai.ProviderBalanceText
import app.amber.feature.ui.components.ui.SiliconFlowPowerByIcon
import app.amber.feature.ui.pages.setting.components.ProviderConfigure
import app.amber.feature.ui.pages.setting.components.ProviderConnectionTester
import app.amber.feature.ui.pages.setting.components.SettingProviderBalanceOption
import app.amber.feature.ui.pages.setting.components.isUsingDefaultBaseUrl
import app.amber.feature.ui.pages.setting.components.resetBaseUrlToDefault

@Composable
internal fun SettingProviderConfigPage(
    provider: ProviderSetting,
    onEdit: (ProviderSetting) -> Unit,
    onDelete: () -> Unit
) {
    var internalProvider by remember(provider) { mutableStateOf(provider) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .imePadding(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item(key = "provider_config") {
            ProviderConfigure(
                provider = internalProvider,
                // Async outcomes (Codex OAuth login, listModels refresh) commit straight to the
                // top-level settings — without this, the user has to remember to hit Save before
                // switching to the Models tab, and we end up with "Codex login succeeded but Models
                // tab shows empty" reports.
                onCommit = { committed ->
                    internalProvider = committed  // keep local in sync so the UI doesn't snap back
                    onEdit(committed)
                },
                onEdit = {
                    internalProvider = it
                },
            )
        }

        if (internalProvider is ProviderSetting.OpenAI) {
            item(key = "provider_balance") {
                SettingProviderBalanceOption(
                    provider = internalProvider,
                    balanceOption = internalProvider.balanceOption,
                    onEdit = { internalProvider = internalProvider.copyProvider(balanceOption = it) }
                )
            }
            item(key = "provider_balance_text") {
                ProviderBalanceText(providerSetting = internalProvider, style = MaterialTheme.typography.labelSmall)
            }
        }

        item(key = "provider_actions") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ProviderConnectionTester(
                    internalProvider = internalProvider,
                )

                Spacer(Modifier.weight(1f))

                IconButton(
                    onClick = {
                        showDeleteDialog = true
                    },
                ) {
                    Icon(HugeIcons.Delete01, null)
                }

                IconButton(
                    onClick = {
                        internalProvider = internalProvider.resetBaseUrlToDefault()
                    },
                    enabled = !internalProvider.isUsingDefaultBaseUrl(),
                ) {
                    Icon(
                        imageVector = HugeIcons.Refresh03,
                        contentDescription = stringResource(R.string.setting_model_page_reset_to_default)
                    )
                }

                Button(
                    onClick = {
                        onEdit(internalProvider)
                    }
                ) {
                    Text(stringResource(R.string.setting_provider_page_save))
                }
            }
        }

        // 硅基流动图标
        val siliconFlowProvider = internalProvider as? ProviderSetting.OpenAI
        if (siliconFlowProvider?.baseUrl?.contains("siliconflow.cn") == true) {
            item(key = "siliconflow_powered_by") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    SiliconFlowPowerByIcon()
                }
            }
        }
    }

    // Delete confirmation dialog
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = {
                Text(stringResource(R.string.confirm_delete))
            },
            text = {
                Text(stringResource(R.string.setting_provider_page_delete_dialog_text))
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteDialog = false
                        onDelete()
                    }
                ) {
                    Text(stringResource(R.string.delete))
                }
            }
        )
    }
}
