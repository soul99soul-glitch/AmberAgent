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
import androidx.compose.ui.unit.dp
import app.amber.feature.board.hotlist.HotListProviderIds
import app.amber.feature.board.hotlist.providers.CustomHotListFieldMapping
import app.amber.feature.board.hotlist.providers.CustomHotListSourceTypes
import app.amber.feature.board.hotlist.providers.NewsNowPreset
import app.amber.feature.board.hotlist.providers.NewsNowPresets
import me.rerere.rikkahub.data.db.entity.HotListSourceEntity
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
            Text("数据源", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { showNewsNowDialog = true }) {
                    Text("+ NewsNow")
                }
                TextButton(onClick = { showDialog = true }) {
                    Text("+ 自定义")
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
                            Text(source.label, style = MaterialTheme.typography.bodyMedium)
                            if (!source.verified) {
                                Text("默认关闭", style = MaterialTheme.typography.labelSmall, color = workspaceColors().muted)
                            }
                        }
                    }
                }
            }
        }
        if (customSources.isNotEmpty()) {
            Text("自定义源", style = MaterialTheme.typography.labelMedium, color = workspaceColors().muted)
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
                        Text("删除")
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
        title = { Text("一键添加 NewsNow 源") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "通过 newsnow.busiyi.world 聚合接入第三方热榜（知乎、微博、抖音等），保存后会作为自定义 JSON 源参与下次刷新。",
                    style = MaterialTheme.typography.bodySmall,
                    color = workspaceColors().muted,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    NewsNowPresets.ALL.forEach { preset ->
                        val alreadyAdded = preset.id in existingIds
                        val checked = preset.id in selectedIds
                        SourceChip(
                            selected = checked || alreadyAdded,
                            label = if (alreadyAdded) "${preset.displayName} ·已加" else preset.displayName,
                            onClick = {
                                if (!alreadyAdded) {
                                    selectedIds = if (checked) selectedIds - preset.id else selectedIds + preset.id
                                }
                            },
                        )
                    }
                }
                Text(
                    "已加入的源不会重复添加；如需调整请到下方的「自定义源」开关或删除。",
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
                Text("添加 ${selectedIds.size} 个")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
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
        title = { Text("添加自定义热榜源") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("名称") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("RSS 或 JSON URL") },
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
                    OutlinedTextField(value = itemsPath, onValueChange = { itemsPath = it }, label = { Text("列表路径") }, singleLine = true)
                    OutlinedTextField(value = titlePath, onValueChange = { titlePath = it }, label = { Text("标题路径") }, singleLine = true)
                    OutlinedTextField(value = urlPath, onValueChange = { urlPath = it }, label = { Text("链接路径") }, singleLine = true)
                    OutlinedTextField(value = heatPath, onValueChange = { heatPath = it }, label = { Text("热度路径（可选）") }, singleLine = true)
                    OutlinedTextField(value = imagePath, onValueChange = { imagePath = it }, label = { Text("图片路径（可选）") }, singleLine = true)
                }
                Text(
                    "保存后会参与下一次热榜刷新；仅支持 http/https。JSON 路径使用点号，例如 data.list / title / url。",
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
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
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

data class CustomHotListSourceDraft(
    val name: String,
    val sourceType: String,
    val url: String,
    val mapping: CustomHotListFieldMapping,
)

data class HotListSourceOption(
    val id: String,
    val label: String,
    val verified: Boolean,
)

val HOT_LIST_SOURCE_OPTIONS = listOf(
    HotListSourceOption(HotListProviderIds.BILIBILI, "B站热门", true),
    HotListSourceOption(HotListProviderIds.HACKER_NEWS, "HackerNews", true),
    HotListSourceOption(HotListProviderIds.ARXIV_AI, "arXiv AI", true),
    HotListSourceOption(HotListProviderIds.INFOQ_AI, "InfoQ AI", true),
    HotListSourceOption(HotListProviderIds.WEIBO, "微博热搜", false),
    HotListSourceOption(HotListProviderIds.ZHIHU, "知乎热榜", false),
    HotListSourceOption(HotListProviderIds.KR36, "36Kr", false),
    HotListSourceOption(HotListProviderIds.HUGGINGFACE_PAPERS, "HF Papers", false),
    HotListSourceOption(HotListProviderIds.GITHUB_TRENDING_AI, "GitHub AI", false),
)
