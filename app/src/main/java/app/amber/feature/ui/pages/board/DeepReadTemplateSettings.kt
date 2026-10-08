package app.amber.feature.ui.pages.board

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import app.amber.agent.R
import app.amber.feature.board.DeepReadTemplateIds
import app.amber.feature.board.TodayBoardSetting
import app.amber.feature.board.hotlist.deepread.template.DeepReadSynthesisTemplate
import app.amber.feature.board.hotlist.deepread.template.DeepReadTemplatePackage
import app.amber.feature.board.hotlist.deepread.template.DeepReadRenderedTemplate
import app.amber.feature.board.hotlist.deepread.template.DeepReadTemplateRenderer
import app.amber.core.font.SlidesFontRepository
import app.amber.feature.ui.components.ui.workspaceColors
import app.amber.feature.ui.theme.LocalAmberTokens
import app.amber.feature.ui.theme.LocalAmberType
import com.composables.icons.lucide.CodeXml
import com.composables.icons.lucide.Lucide

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
    // The preview represents the same light paper used by the reader in both
    // app themes; only surrounding settings chrome follows the active theme.
    val darkTheme = false
    val sampleTitle = stringResource(R.string.deep_read_sample_title)
    val templateUnavailableMessage = stringResource(R.string.deep_read_template_unavailable)
    val templatePreviewFailedTemplate = stringResource(
        R.string.deep_read_template_preview_failed,
        "__TEMPLATE_ERROR__",
    )
    val sampleOutput = remember { DeepReadTemplateRenderer.sampleOutput() }
    val selectedTemplateId = DeepReadTemplateIds.normalize(board.deepReadTemplateId)
    val selectedTemplateName = DeepReadTemplateCatalog.name(
        selectedTemplateId,
        customTemplates.firstOrNull { it.id == board.deepReadTemplateId }?.name,
    )
    fun previewSelectedTemplate() {
        val synthesisKind = DeepReadSynthesisTemplate.fromWireId(selectedTemplateId)
        previewTarget = when {
            synthesisKind != null -> DeepReadTemplateRenderer.renderTemplateArticle(
                article = DeepReadTemplateRenderer.sampleTemplateArticle(
                    // Auto previews the shape it most often resolves to.
                    if (synthesisKind == DeepReadSynthesisTemplate.AUTO) {
                        DeepReadSynthesisTemplate.BRIEF
                    } else {
                        synthesisKind
                    },
                ),
                fontCss = fontCss,
                darkTheme = darkTheme,
            ).toPreviewTarget(selectedTemplateName)
            selectedTemplateId == DeepReadTemplateIds.COMPOSE_MAGAZINE ||
                selectedTemplateId == DeepReadTemplateIds.EDITORIAL_SLANT ->
                DeepReadTemplateRenderer.renderEditorialSlant(
                    title = sampleTitle,
                    output = sampleOutput,
                    fontCss = fontCss,
                    darkTheme = darkTheme,
                )
                    .toPreviewTarget(selectedTemplateName)
            else -> {
                val template = customTemplates.firstOrNull { it.id == board.deepReadTemplateId }
                if (template == null) {
                    TemplatePreviewTarget(
                        selectedTemplateName,
                        "<html><body><p>$templateUnavailableMessage</p></body></html>",
                    )
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
                        TemplatePreviewTarget(
                            template.name,
                            "<html><body><p>${templatePreviewFailedTemplate.replace("__TEMPLATE_ERROR__", it.message.orEmpty())}</p></body></html>",
                        )
                    }
                }
            }
        }
    }
    val tokens = LocalAmberTokens.current
    val cardShape = RoundedCornerShape(14.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 16.dp, bottom = 4.dp),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = cardShape,
            color = tokens.surface,
            border = androidx.compose.foundation.BorderStroke(1.dp, tokens.line),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 64.dp)
                    .padding(horizontal = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.deep_read_template_title),
                        style = LocalAmberType.current.body.copy(fontWeight = FontWeight.SemiBold),
                        color = tokens.ink,
                    )
                    Text(
                        stringResource(R.string.deep_read_template_description),
                        style = LocalAmberType.current.secondary,
                        color = tokens.ink2,
                        maxLines = 2,
                    )
                }
                TextButton(
                    onClick = onCreateTemplate,
                    contentPadding = PaddingValues(horizontal = 5.dp, vertical = 0.dp),
                ) {
                    Text(stringResource(R.string.deep_read_template_create), style = LocalAmberType.current.secondary, color = tokens.accent)
                }
            }
        }

        Spacer(Modifier.height(28.dp))
        TemplateSectionLabel(stringResource(R.string.deep_read_template_label))
        Spacer(Modifier.height(10.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            DeepReadTemplateCatalog.options.forEach { templateId ->
                TemplateChip(
                    selected = selectedTemplateId == templateId,
                    label = DeepReadTemplateCatalog.name(templateId),
                    icon = DeepReadTemplateCatalog.icon(templateId),
                    onClick = { onSelect(templateId) },
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = { previewSelectedTemplate() },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            shape = RoundedCornerShape(15.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = tokens.ink,
                contentColor = tokens.bg,
            ),
        ) {
            Text(stringResource(R.string.deep_read_template_preview, selectedTemplateName), style = LocalAmberType.current.secondary)
        }

        Spacer(Modifier.height(28.dp))
        TemplateSectionLabel(stringResource(R.string.deep_read_template_custom))
        Spacer(Modifier.height(10.dp))
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = cardShape,
            color = tokens.surface,
            border = androidx.compose.foundation.BorderStroke(1.dp, tokens.line),
        ) {
            if (customTemplates.isEmpty()) {
                Column(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("还没有自定义模板", style = LocalAmberType.current.secondary, color = tokens.ink2)
                    Text("创建后会出现在这里", style = LocalAmberType.current.meta.copy(fontSize = 11.sp), color = tokens.ink3)
                }
            } else {
                Column {
                    customTemplates.forEachIndexed { index, template ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(template.id) }
                                .heightIn(min = 64.dp)
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
                                Icon(Lucide.CodeXml, contentDescription = null, modifier = Modifier.size(17.dp), tint = tokens.ink2)
                            }
                            Text(
                                template.name,
                                Modifier.weight(1f),
                                style = LocalAmberType.current.body.copy(fontWeight = FontWeight.SemiBold),
                                color = if (board.deepReadTemplateId == template.id) tokens.accent else tokens.ink,
                                maxLines = 1,
                            )
                            TextButton(
                                onClick = { onDelete(template) },
                                contentPadding = PaddingValues(horizontal = 3.dp, vertical = 0.dp),
                            ) {
                                Text(stringResource(R.string.delete), style = LocalAmberType.current.secondary, color = MaterialTheme.colorScheme.error)
                            }
                        }
                        if (index != customTemplates.lastIndex) {
                            androidx.compose.material3.HorizontalDivider(Modifier.padding(start = 56.dp), color = tokens.line)
                        }
                    }
                }
            }
        }
        if (invalidTemplateCount > 0) {
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.deep_read_template_invalid_count, invalidTemplateCount),
                style = LocalAmberType.current.secondary,
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
private fun TemplateSectionLabel(label: String) {
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
private fun TemplateChip(
    selected: Boolean,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
) {
    val tokens = LocalAmberTokens.current
    Surface(
        shape = androidx.compose.foundation.shape.RoundedCornerShape(50),
        color = if (selected) tokens.accent else tokens.surface2,
        contentColor = if (selected) tokens.accentInk else tokens.ink2,
        border = androidx.compose.foundation.BorderStroke(1.dp, if (selected) tokens.accent else tokens.line2),
        modifier = modifier
            .heightIn(min = 32.dp)
            .clickable { onClick() },
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(13.dp))
            }
            Text(label, style = LocalAmberType.current.secondary)
        }
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
        title = { Text(stringResource(R.string.deep_read_template_preview, target.name)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.deep_read_template_preview_description),
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
                    backgroundColor = Color(0xFFFAFAF8),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.update_card_close))
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
