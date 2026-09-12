package app.amber.feature.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.amber.agent.R
import app.amber.ai.provider.ProviderSetting
import app.amber.feature.ui.pages.setting.components.ProviderSheetGrabber
import app.amber.feature.ui.pages.setting.components.ProviderSplitBar
import app.amber.feature.ui.theme.LocalAmberTokens
import app.amber.feature.ui.theme.LocalAmberType

@Composable
internal fun ProviderFileImportPreview(
    providers: List<ProviderSetting>,
    existingProviders: List<ProviderSetting>,
    saving: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (List<ProviderSetting>) -> Unit,
) {
    val t = LocalAmberTokens.current
    val type = LocalAmberType.current
    val duplicates = remember(providers, existingProviders) {
        val seen = existingProviders.map { it.name.trim().lowercase(java.util.Locale.ROOT) }.toMutableSet()
        providers.map { !seen.add(it.name.trim().lowercase(java.util.Locale.ROOT)) }
    }
    var selected by remember(providers) {
        mutableStateOf(providers.indices.filterNot { duplicates[it] }.toSet())
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = t.bg,
        dragHandle = { ProviderSheetGrabber() },
    ) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.8f)) {
            Text(
                stringResource(R.string.provider_file_import_title),
                style = type.screenTitle,
                color = t.ink,
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp),
            )
            Text(
                stringResource(R.string.provider_file_import_hint),
                style = type.secondary,
                color = t.ink3,
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp),
            )
            LazyColumn(Modifier.weight(1f)) {
                itemsIndexed(providers) { index, provider ->
                    Row(
                        Modifier.fillMaxWidth()
                            .toggleable(
                                value = index in selected,
                                enabled = !saving,
                                role = Role.Checkbox,
                                onValueChange = { checked ->
                                    selected = if (checked) selected + index else selected - index
                                },
                            )
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Checkbox(
                            checked = index in selected,
                            enabled = !saving,
                            onCheckedChange = null,
                        )
                        Column(Modifier.weight(1f)) {
                            Text(provider.name, style = type.body, color = t.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(
                                stringResource(R.string.setting_provider_page_model_count, provider.models.size),
                                style = type.meta,
                                color = t.ink3,
                            )
                            if (duplicates[index]) Text(
                                stringResource(R.string.provider_file_import_duplicate),
                                style = type.secondary,
                                color = t.accent,
                            )
                        }
                    }
                }
            }
            ProviderSplitBar(
                cancelText = stringResource(R.string.cancel),
                onCancel = onDismiss,
                confirmText = stringResource(R.string.provider_file_import_confirm, selected.size),
                confirmEnabled = selected.isNotEmpty() && !saving,
                onConfirm = { onConfirm(providers.filterIndexed { index, _ -> index in selected }) },
            )
        }
    }
}
