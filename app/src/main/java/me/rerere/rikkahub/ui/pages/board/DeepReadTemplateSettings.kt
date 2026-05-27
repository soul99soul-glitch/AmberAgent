package me.rerere.rikkahub.ui.pages.board

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.data.agent.board.DeepReadTemplateIds
import me.rerere.rikkahub.data.agent.board.TodayBoardSetting
import me.rerere.rikkahub.data.agent.board.hotlist.deepread.template.DeepReadTemplatePackage
import me.rerere.rikkahub.data.agent.board.hotlist.deepread.template.DeepReadRenderedTemplate
import me.rerere.rikkahub.data.agent.board.hotlist.deepread.template.DeepReadTemplateRenderer
import me.rerere.rikkahub.data.font.SlidesFontRepository
import me.rerere.rikkahub.ui.components.ui.workspaceColors
import me.rerere.rikkahub.ui.theme.LocalDarkMode

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DeepReadTemplateSettingsRow(
    board: TodayBoardSetting,
    customTemplates: List<DeepReadTemplatePackage>,
    invalidTemplateCount: Int,
    fontCss: String,
    fontRepository: SlidesFontRepository,
    onSelect: (String) -> Unit,
    onDelete: (DeepReadTemplatePackage) -> Unit,
    onCreateTemplate: () -> Unit,
) {
    var previewTarget by remember { mutableStateOf<TemplatePreviewTarget?>(null) }
    val darkTheme = LocalDarkMode.current
    val sampleTitle = "具身智能进入家庭前夜"
    val sampleOutput = remember { DeepReadTemplateRenderer.sampleOutput() }
    val selectedTemplateName = when (board.deepReadTemplateId) {
        DeepReadTemplateIds.COMPOSE_MAGAZINE -> "默认杂志"
        DeepReadTemplateIds.EDITORIAL_SLANT -> "斜切图文"
        else -> customTemplates.firstOrNull { it.id == board.deepReadTemplateId }?.name ?: "当前模板"
    }
    fun previewSelectedTemplate() {
        previewTarget = when (board.deepReadTemplateId) {
            DeepReadTemplateIds.COMPOSE_MAGAZINE,
            DeepReadTemplateIds.EDITORIAL_SLANT -> DeepReadTemplateRenderer.renderEditorialSlant(
                title = sampleTitle,
                output = sampleOutput,
                fontCss = fontCss,
                darkTheme = darkTheme,
            )
                .toPreviewTarget(selectedTemplateName)
            else -> {
                val template = customTemplates.firstOrNull { it.id == board.deepReadTemplateId }
                if (template == null) {
                    TemplatePreviewTarget(selectedTemplateName, "<html><body><p>当前模板不可用</p></body></html>")
                } else {
                    runCatching {
                        DeepReadTemplateRenderer.renderCustom(
                            title = sampleTitle,
                            output = sampleOutput,
                            templateHtml = template.html,
                            fontCss = fontCss,
                            darkTheme = darkTheme,
                        )
                            .toPreviewTarget(template.name)
                    }.getOrElse {
                        TemplatePreviewTarget(template.name, "<html><body><p>模板预览失败：${it.message.orEmpty()}</p></body></html>")
                    }
                }
            }
        }
    }
    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("深度阅读模板", style = MaterialTheme.typography.titleSmall)
            TextButton(onClick = onCreateTemplate) {
                Text("生成新模板")
            }
        }
        Text(
            "模板会持久化复用；AI 只生成版式模板。预览使用固定样稿，只看 UI 风格，不混入真实新闻内容。",
            style = MaterialTheme.typography.bodySmall,
            color = workspaceColors().muted,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TemplateChip(
                selected = board.deepReadTemplateId == DeepReadTemplateIds.COMPOSE_MAGAZINE,
                label = "默认杂志",
                onClick = { onSelect(DeepReadTemplateIds.COMPOSE_MAGAZINE) },
            )
            TemplateChip(
                selected = board.deepReadTemplateId == DeepReadTemplateIds.EDITORIAL_SLANT,
                label = "斜切图文",
                onClick = { onSelect(DeepReadTemplateIds.EDITORIAL_SLANT) },
            )
        }
        OutlinedButton(
            onClick = { previewSelectedTemplate() },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.primary),
        ) {
            Text("预览当前模板：$selectedTemplateName")
        }
        if (customTemplates.isNotEmpty()) {
            Text("自定义模板", style = MaterialTheme.typography.labelMedium, color = workspaceColors().muted)
            customTemplates.forEach { template ->
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TemplateChip(
                        selected = board.deepReadTemplateId == template.id,
                        label = template.name,
                        onClick = { onSelect(template.id) },
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { onDelete(template) }) {
                        Text("删除")
                    }
                }
            }
        }
        if (invalidTemplateCount > 0) {
            Text(
                "$invalidTemplateCount 个本地模板已失效，阅读页会自动回退默认排版。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
    previewTarget?.let { target ->
        DeepReadTemplatePreviewDialog(
            target = target,
            fontRepository = fontRepository,
            textScale = board.deepReadFontScale,
            onDismiss = { previewTarget = null },
        )
    }
}

@Composable
private fun TemplateChip(
    selected: Boolean,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = androidx.compose.foundation.shape.RoundedCornerShape(50),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier.clickable { onClick() },
    ) {
        Text(label, modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp), style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun DeepReadTemplatePreviewDialog(
    target: TemplatePreviewTarget,
    fontRepository: SlidesFontRepository,
    textScale: Float,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${target.name} 预览") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "以下是固定占位样稿，只用于判断模板版式和字体层级。",
                    style = MaterialTheme.typography.bodySmall,
                    color = workspaceColors().muted,
                )
                DeepReadStaticTemplateWebView(
                    html = target.html,
                    modifier = Modifier.fillMaxWidth().height(520.dp),
                    baseUrl = DEEP_READ_TEMPLATE_PREVIEW_BASE_URL,
                    allowedImageUrls = target.allowedImageUrls,
                    fontRepository = fontRepository,
                    textScale = textScale,
                    backgroundColor = MaterialTheme.colorScheme.surface,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭")
            }
        },
    )
}

private data class TemplatePreviewTarget(
    val name: String,
    val html: String,
    val allowedImageUrls: Set<String> = emptySet(),
)

private fun DeepReadRenderedTemplate.toPreviewTarget(name: String): TemplatePreviewTarget =
    TemplatePreviewTarget(
        name = name,
        html = html,
        allowedImageUrls = allowedImageUrls,
    )
