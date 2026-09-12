package app.amber.feature.ui.pages.board

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.amber.agent.R
import app.amber.feature.board.hotlist.HotListProviderIds
import app.amber.feature.board.hotlist.providers.CustomHotListFieldMapping
import app.amber.feature.board.hotlist.providers.CustomHotListSourceTypes
import app.amber.feature.board.hotlist.providers.NewsNowPreset
import app.amber.feature.board.hotlist.providers.NewsNowPresets
import app.amber.agent.data.db.entity.HotListSourceEntity
import app.amber.feature.ui.components.ui.Switch
import app.amber.feature.ui.components.ui.workspaceColors
import app.amber.feature.ui.theme.LocalAmberTokens
import app.amber.feature.ui.theme.LocalAmberType
import com.composables.icons.lucide.FileText
import com.composables.icons.lucide.Lucide
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
    val tokens = LocalAmberTokens.current
    val cardShape = RoundedCornerShape(14.dp)
    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = cardShape,
            color = tokens.surface,
            border = androidx.compose.foundation.BorderStroke(1.dp, tokens.line),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.board_sources_title), style = LocalAmberType.current.sessionTitle, color = tokens.ink)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    SourceQuietAction("+ NewsNow") { showNewsNowDialog = true }
                    SourceQuietAction(stringResource(R.string.board_custom_source_add)) { showDialog = true }
                }
            }
        }

        SourceSectionLabel(stringResource(R.string.board_hotlist_sources))
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = cardShape,
            color = tokens.surface,
            border = androidx.compose.foundation.BorderStroke(1.dp, tokens.line),
        ) {
            Column {
                HOT_LIST_SOURCE_OPTIONS.chunked(2).forEachIndexed { rowIndex, row ->
                    Row(Modifier.fillMaxWidth()) {
                        row.forEachIndexed { cellIndex, source ->
                            SourceGridCell(
                                modifier = Modifier.weight(1f),
                                source = source,
                                checked = source.id in enabledBuiltIns,
                                onToggle = { onToggleBuiltIn(source.id) },
                                rightBorder = cellIndex == 0 && row.size > 1,
                                bottomBorder = rowIndex < (HOT_LIST_SOURCE_OPTIONS.size + 1) / 2 - 1,
                            )
                        }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }

        SourceSectionLabel(stringResource(R.string.board_custom_sources))
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = cardShape,
            color = tokens.surface,
            border = androidx.compose.foundation.BorderStroke(1.dp, tokens.line),
        ) {
            if (customSources.isEmpty()) {
                Text(
                    "还没有自定义来源",
                    Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 18.dp),
                    style = LocalAmberType.current.secondary,
                    color = tokens.ink3,
                )
            } else {
                Column {
                    customSources.forEachIndexed { index, source ->
                        CustomSourceRow(
                            source = source,
                            onToggle = { onToggleCustom(source) },
                            onDelete = { onDeleteCustom(source) },
                            showDivider = index != customSources.lastIndex,
                        )
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

@Composable
private fun SourceSectionLabel(label: String) {
    val tokens = LocalAmberTokens.current
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("//", style = LocalAmberType.current.eyebrow, color = tokens.accent)
        Text(label, style = LocalAmberType.current.eyebrow, color = tokens.ink2)
        androidx.compose.material3.HorizontalDivider(Modifier.weight(1f), color = tokens.line)
    }
}

@Composable
private fun SourceQuietAction(label: String, onClick: () -> Unit) {
    val tokens = LocalAmberTokens.current
    TextButton(
        onClick = onClick,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 5.dp, vertical = 0.dp),
    ) {
        Text(label, style = LocalAmberType.current.secondary, color = tokens.accent, maxLines = 1)
    }
}

@Composable
private fun SourceGridCell(
    modifier: Modifier,
    source: HotListSourceOption,
    checked: Boolean,
    onToggle: () -> Unit,
    rightBorder: Boolean,
    bottomBorder: Boolean,
) {
    val tokens = LocalAmberTokens.current
    Row(
        modifier
            .height(if (source.verified) 52.dp else 64.dp)
            .clickable(onClick = onToggle)
            .then(if (rightBorder || bottomBorder) Modifier.border(0.5.dp, tokens.line) else Modifier)
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                hotListSourceLabel(source.id),
                style = LocalAmberType.current.body.copy(fontSize = 15.sp),
                color = tokens.ink,
                maxLines = 1,
            )
            if (!source.verified) {
                Text(
                    stringResource(R.string.board_source_default_off),
                    style = LocalAmberType.current.meta.copy(fontSize = 11.sp),
                    color = tokens.ink3,
                    maxLines = 1,
                )
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = { onToggle() },
            trackColor = tokens.accent,
            trackColorUnchecked = tokens.surface2,
            thumbColor = tokens.accentInk,
            thumbColorUnchecked = tokens.ink2,
        )
    }
}

@Composable
private fun CustomSourceRow(
    source: HotListSourceEntity,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
    showDivider: Boolean,
) {
    val tokens = LocalAmberTokens.current
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .height(64.dp)
                .clickable(onClick = onToggle)
                .padding(horizontal = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            androidx.compose.foundation.layout.Box(
                Modifier
                    .size(32.dp)
                    .background(tokens.surface2, RoundedCornerShape(9.dp))
                    .border(1.dp, tokens.line, RoundedCornerShape(9.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Lucide.FileText, contentDescription = null, modifier = Modifier.size(17.dp), tint = tokens.ink2)
            }
            Column(Modifier.weight(1f)) {
                Text(source.displayName, style = LocalAmberType.current.body.copy(fontWeight = FontWeight.SemiBold), color = tokens.ink, maxLines = 1)
                Text(
                    "${source.sourceType.uppercase()} · ${source.url}",
                    style = LocalAmberType.current.meta.copy(fontSize = 11.sp),
                    color = tokens.ink3,
                    maxLines = 1,
                )
            }
            Switch(
                checked = source.enabled,
                onCheckedChange = { onToggle() },
                trackColor = tokens.accent,
                trackColorUnchecked = tokens.surface2,
                thumbColor = tokens.accentInk,
                thumbColorUnchecked = tokens.ink2,
            )
            TextButton(
                onClick = onDelete,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 3.dp, vertical = 0.dp),
            ) {
                Text(stringResource(R.string.delete), style = LocalAmberType.current.secondary, color = MaterialTheme.colorScheme.error)
            }
        }
        if (showDivider) {
            androidx.compose.material3.HorizontalDivider(Modifier.padding(start = 56.dp), color = tokens.line)
        }
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
