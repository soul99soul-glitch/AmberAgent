package app.amber.feature.ui.pages.board

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.amber.agent.R
import app.amber.feature.board.hotlist.HotListProviderIds
import app.amber.feature.board.hotlist.providers.CustomHotListFieldMapping
import app.amber.feature.board.hotlist.providers.CustomHotListSourceTypes
import app.amber.feature.board.hotlist.providers.NewsNowPreset
import app.amber.feature.board.hotlist.providers.NewsNowPresets
import app.amber.agent.data.db.entity.HotListSourceEntity
import app.amber.feature.ui.components.ui.Switch
import app.amber.feature.ui.components.ui.workspaceColors
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HotListSourceSettings(
    enabledBuiltIns: Set<String>,
    customSources: List<HotListSourceEntity>,
    onToggleBuiltIn: (String) -> Unit,
    onToggleCustom: (HotListSourceEntity) -> Unit,
    onDeleteCustom: (HotListSourceEntity) -> Unit,
    onSaveCustom: (CustomHotListSourceDraft) -> Unit,
    onAddNewsNowPresets: (List<NewsNowPreset>) -> Unit,
) {
    var showDialog by rememberSaveable { mutableStateOf(false) }
    var showNewsNowDialog by rememberSaveable { mutableStateOf(false) }
    val existingNewsNowIds = remember(customSources) {
        customSources.asSequence()
            .filter { it.id.startsWith(NewsNowPresets.ID_PREFIX) }
            .map { it.id.removePrefix(NewsNowPresets.ID_PREFIX) }
            .toSet()
    }
    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.board_sources_title), style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { showNewsNowDialog = true }) {
                    Text("+ NewsNow")
                }
                TextButton(onClick = { showDialog = true }) {
                    Text(stringResource(R.string.board_custom_source_add))
                }
            }
        }
        HOT_LIST_SOURCE_OPTIONS.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { source ->
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                        Switch(checked = source.id in enabledBuiltIns, onCheckedChange = { onToggleBuiltIn(source.id) })
                        Spacer(Modifier.width(6.dp))
                        Column {
                            Text(hotListSourceLabel(source.id), style = MaterialTheme.typography.bodyMedium)
                            if (!source.verified) {
                                Text(stringResource(R.string.board_source_default_off), style = MaterialTheme.typography.labelSmall, color = workspaceColors().muted)
                            }
                        }
                    }
                }
            }
        }
        if (customSources.isNotEmpty()) {
            Text(stringResource(R.string.board_custom_sources), style = MaterialTheme.typography.labelMedium, color = workspaceColors().muted)
            customSources.forEach { source ->
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Switch(checked = source.enabled, onCheckedChange = { onToggleCustom(source) })
                    Column(Modifier.weight(1f)) {
                        Text(source.displayName, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "${source.sourceType.uppercase()} · ${source.url}",
                            style = MaterialTheme.typography.labelSmall,
                            color = workspaceColors().muted,
                            maxLines = 1,
                        )
                    }
                    TextButton(onClick = { onDeleteCustom(source) }) {
                        Text(stringResource(R.string.delete))
                    }
                }
            }
        }
    }
    if (showDialog) {
        CustomHotListSourceDialog(
            onDismiss = { showDialog = false },
            onSave = { draft ->
                onSaveCustom(draft)
                showDialog = false
            },
        )
    }
    if (showNewsNowDialog) {
        NewsNowPresetDialog(
            existingIds = existingNewsNowIds,
            onDismiss = { showNewsNowDialog = false },
            onConfirm = { selected ->
                onAddNewsNowPresets(selected)
                showNewsNowDialog = false
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NewsNowPresetDialog(
    existingIds: Set<String>,
    onDismiss: () -> Unit,
    onConfirm: (List<NewsNowPreset>) -> Unit,
) {
    var selectedIds by rememberSaveable { mutableStateOf<Set<String>>(emptySet()) }
    val confirmable = selectedIds.isNotEmpty()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.board_newsnow_add_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    stringResource(R.string.board_newsnow_add_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = workspaceColors().muted,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    NewsNowPresets.ALL.forEach { preset ->
                        val alreadyAdded = preset.id in existingIds
                        val checked = preset.id in selectedIds
                        SourceChip(
                            selected = checked || alreadyAdded,
                            label = if (alreadyAdded) {
                                stringResource(R.string.board_newsnow_added, newsNowPresetDisplayName(preset.id))
                            } else {
                                newsNowPresetDisplayName(preset.id)
                            },
                            onClick = {
                                if (!alreadyAdded) {
                                    selectedIds = if (checked) selectedIds - preset.id else selectedIds + preset.id
                                }
                            },
                        )
                    }
                }
                Text(
                    stringResource(R.string.board_newsnow_existing_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = workspaceColors().muted,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = confirmable,
                onClick = {
                    val picked = NewsNowPresets.ALL.filter { it.id in selectedIds && it.id !in existingIds }
                    onConfirm(picked)
                },
            ) {
                Text(stringResource(R.string.board_newsnow_add_selected, selectedIds.size))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CustomHotListSourceDialog(
    onDismiss: () -> Unit,
    onSave: (CustomHotListSourceDraft) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("") }
    var url by rememberSaveable { mutableStateOf("") }
    var type by rememberSaveable { mutableStateOf(CustomHotListSourceTypes.RSS) }
    var itemsPath by rememberSaveable { mutableStateOf("data.list") }
    var titlePath by rememberSaveable { mutableStateOf("title") }
    var urlPath by rememberSaveable { mutableStateOf("url") }
    var heatPath by rememberSaveable { mutableStateOf("") }
    var imagePath by rememberSaveable { mutableStateOf("") }
    val parsedUrl = url.trim().toHttpUrlOrNull()
    val valid = name.trim().isNotEmpty() && parsedUrl != null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.board_custom_source_add_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.board_source_name)) },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text(stringResource(R.string.board_source_url)) },
                    singleLine = true,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SourceChip(selected = type == CustomHotListSourceTypes.RSS, label = "RSS") {
                        type = CustomHotListSourceTypes.RSS
                    }
                    SourceChip(selected = type == CustomHotListSourceTypes.JSON, label = "JSON") {
                        type = CustomHotListSourceTypes.JSON
                    }
                }
                if (type == CustomHotListSourceTypes.JSON) {
                    OutlinedTextField(value = itemsPath, onValueChange = { itemsPath = it }, label = { Text(stringResource(R.string.board_source_items_path)) }, singleLine = true)
                    OutlinedTextField(value = titlePath, onValueChange = { titlePath = it }, label = { Text(stringResource(R.string.board_source_title_path)) }, singleLine = true)
                    OutlinedTextField(value = urlPath, onValueChange = { urlPath = it }, label = { Text(stringResource(R.string.board_source_link_path)) }, singleLine = true)
                    OutlinedTextField(value = heatPath, onValueChange = { heatPath = it }, label = { Text(stringResource(R.string.board_source_heat_path)) }, singleLine = true)
                    OutlinedTextField(value = imagePath, onValueChange = { imagePath = it }, label = { Text(stringResource(R.string.board_source_image_path)) }, singleLine = true)
                }
                Text(
                    stringResource(R.string.board_custom_source_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = workspaceColors().muted,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    onSave(
                        CustomHotListSourceDraft(
                            name = name,
                            sourceType = type,
                            url = url,
                            mapping = CustomHotListFieldMapping(
                                itemsPath = itemsPath,
                                titlePath = titlePath,
                                urlPath = urlPath,
                                heatPath = heatPath,
                                imagePath = imagePath,
                            ),
                        )
                    )
                },
            ) {
                Text(stringResource(R.string.common_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
private fun SourceChip(selected: Boolean, label: String, onClick: () -> Unit) {
    Surface(
        shape = androidx.compose.foundation.shape.RoundedCornerShape(50),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.clickable { onClick() },
    ) {
        Text(label, modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp), style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun hotListSourceLabel(id: String): String = when (id) {
    HotListProviderIds.BILIBILI -> stringResource(R.string.board_source_bilibili)
    HotListProviderIds.HACKER_NEWS -> stringResource(R.string.board_source_hacker_news)
    HotListProviderIds.ARXIV_AI -> stringResource(R.string.board_source_arxiv_ai)
    HotListProviderIds.INFOQ_AI -> stringResource(R.string.board_source_infoq_ai)
    HotListProviderIds.WEIBO -> stringResource(R.string.board_source_weibo)
    HotListProviderIds.ZHIHU -> stringResource(R.string.board_source_zhihu)
    HotListProviderIds.KR36 -> stringResource(R.string.board_source_36kr)
    HotListProviderIds.HUGGINGFACE_PAPERS -> stringResource(R.string.board_source_huggingface)
    HotListProviderIds.GITHUB_TRENDING_AI -> stringResource(R.string.board_source_github)
    else -> id
}

@Composable
private fun newsNowPresetDisplayName(id: String): String = when (id) {
    "zhihu" -> stringResource(R.string.board_newsnow_zhihu)
    "weibo" -> stringResource(R.string.board_newsnow_weibo)
    "douyin" -> stringResource(R.string.board_newsnow_douyin)
    "coolapk" -> stringResource(R.string.board_newsnow_coolapk)
    "bilibili-hot-search" -> stringResource(R.string.board_newsnow_bilibili)
    "v2ex-share" -> stringResource(R.string.board_newsnow_v2ex)
    "github-trending-today" -> stringResource(R.string.board_newsnow_github)
    "36kr-quick" -> stringResource(R.string.board_newsnow_36kr)
    "hupu-zhugandaoretie" -> stringResource(R.string.board_newsnow_hupu)
    "xueqiu-hotstock" -> stringResource(R.string.board_newsnow_xueqiu)
    "wallstreetcn-hot" -> stringResource(R.string.board_newsnow_wallstreet)
    "cls-telegraph" -> stringResource(R.string.board_newsnow_cls)
    else -> id
}

data class CustomHotListSourceDraft(
    val name: String,
    val sourceType: String,
    val url: String,
    val mapping: CustomHotListFieldMapping,
)

data class HotListSourceOption(
    val id: String,
    val verified: Boolean,
)

val HOT_LIST_SOURCE_OPTIONS = listOf(
    HotListSourceOption(HotListProviderIds.BILIBILI, true),
    HotListSourceOption(HotListProviderIds.HACKER_NEWS, true),
    HotListSourceOption(HotListProviderIds.ARXIV_AI, true),
    HotListSourceOption(HotListProviderIds.INFOQ_AI, true),
    HotListSourceOption(HotListProviderIds.WEIBO, false),
    HotListSourceOption(HotListProviderIds.ZHIHU, false),
    HotListSourceOption(HotListProviderIds.KR36, false),
    HotListSourceOption(HotListProviderIds.HUGGINGFACE_PAPERS, false),
    HotListSourceOption(HotListProviderIds.GITHUB_TRENDING_AI, false),
)
