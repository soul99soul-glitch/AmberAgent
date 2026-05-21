package me.rerere.rikkahub.ui.pages.board

import android.annotation.SuppressLint
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.viewinterop.AndroidView
import me.rerere.rikkahub.data.agent.board.DeepReadTemplateIds
import me.rerere.rikkahub.data.agent.board.TodayBoardSetting
import me.rerere.rikkahub.data.agent.board.hotlist.deepread.template.DeepReadTemplatePackage
import me.rerere.rikkahub.data.agent.board.hotlist.deepread.template.DeepReadRenderedTemplate
import me.rerere.rikkahub.data.agent.board.hotlist.deepread.template.DeepReadTemplateRenderer
import me.rerere.rikkahub.ui.components.ui.workspaceColors
import java.io.ByteArrayInputStream

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DeepReadTemplateSettingsRow(
    board: TodayBoardSetting,
    customTemplates: List<DeepReadTemplatePackage>,
    invalidTemplateCount: Int,
    generating: Boolean,
    generationError: String?,
    onSelect: (String) -> Unit,
    onDelete: (DeepReadTemplatePackage) -> Unit,
    onGenerate: (String, String) -> Unit,
) {
    var showGenerateDialog by rememberSaveable { mutableStateOf(false) }
    var previewTarget by remember { mutableStateOf<TemplatePreviewTarget?>(null) }
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
            DeepReadTemplateIds.EDITORIAL_SLANT -> DeepReadTemplateRenderer.renderEditorialSlant(sampleTitle, sampleOutput)
                .toPreviewTarget(selectedTemplateName)
            else -> {
                val template = customTemplates.firstOrNull { it.id == board.deepReadTemplateId }
                if (template == null) {
                    TemplatePreviewTarget(selectedTemplateName, "<html><body><p>当前模板不可用</p></body></html>")
                } else {
                    runCatching {
                        DeepReadTemplateRenderer.renderCustom(sampleTitle, sampleOutput, template.html)
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
            TextButton(enabled = !generating, onClick = { showGenerateDialog = true }) {
                Text(if (generating) "生成中..." else "生成新模板")
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
        generationError?.takeIf { it.isNotBlank() }?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        if (invalidTemplateCount > 0) {
            Text(
                "$invalidTemplateCount 个本地模板已失效，阅读页会自动回退默认排版。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
    if (showGenerateDialog) {
        GenerateDeepReadTemplateDialog(
            onDismiss = { showGenerateDialog = false },
            onGenerate = { name, brief ->
                onGenerate(name, brief)
                showGenerateDialog = false
            },
        )
    }
    previewTarget?.let { target ->
        DeepReadTemplatePreviewDialog(
            target = target,
            onDismiss = { previewTarget = null },
        )
    }
}

@Composable
private fun GenerateDeepReadTemplateDialog(
    onDismiss: () -> Unit,
    onGenerate: (String, String) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("News 斜切杂志") }
    var brief by rememberSaveable {
        mutableStateOf("参考高端 News 杂志 App：首屏斜切 Hero 图、强标题排版、红色时间轴、引用块、扩展阅读。")
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("生成深度阅读模板") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("模板名称") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = brief,
                    onValueChange = { brief = it },
                    label = { Text("设计说明") },
                    minLines = 4,
                    maxLines = 6,
                )
                Text(
                    "模型只生成版式模板。保存后可用固定样稿预览版式；真实深度阅读内容仍由 Deep Read Agent 生成。",
                    style = MaterialTheme.typography.bodySmall,
                    color = workspaceColors().muted,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.trim().isNotEmpty(),
                onClick = { onGenerate(name.trim(), brief.trim()) },
            ) {
                Text("生成并保存")
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

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun DeepReadTemplatePreviewDialog(
    target: TemplatePreviewTarget,
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
                AndroidView(
                    modifier = Modifier.fillMaxWidth().height(520.dp),
                    factory = { context ->
                        WebView(context).apply {
                            settings.javaScriptEnabled = false
                            settings.domStorageEnabled = false
                            settings.cacheMode = WebSettings.LOAD_NO_CACHE
                            settings.allowFileAccess = false
                            settings.allowContentAccess = false
                            webViewClient = object : WebViewClient() {
                                override fun shouldInterceptRequest(
                                    view: WebView?,
                                    request: WebResourceRequest?,
                                ): WebResourceResponse? {
                                    val url = request?.url?.toString().orEmpty()
                                    val mainFrame = request?.isForMainFrame == true
                                    if (mainFrame && url == TEMPLATE_PREVIEW_BASE_URL) return null
                                    if (!mainFrame && url in target.allowedImageUrls) return null
                                    return emptyTemplatePreviewResponse()
                                }

                                override fun shouldOverrideUrlLoading(
                                    view: WebView?,
                                    request: WebResourceRequest?,
                                ): Boolean = true
                            }
                            loadDataWithBaseURL(
                                TEMPLATE_PREVIEW_BASE_URL,
                                target.html,
                                "text/html",
                                "utf-8",
                                null,
                            )
                        }
                    },
                    update = { view ->
                        view.loadDataWithBaseURL(
                            TEMPLATE_PREVIEW_BASE_URL,
                            target.html,
                            "text/html",
                            "utf-8",
                            null,
                        )
                    },
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

private fun emptyTemplatePreviewResponse(): WebResourceResponse =
    WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))

private const val TEMPLATE_PREVIEW_BASE_URL = "https://amberagent.template-preview.local/"
