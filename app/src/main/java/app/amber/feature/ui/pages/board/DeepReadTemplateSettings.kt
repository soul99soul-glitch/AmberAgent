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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
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
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import app.amber.agent.R
import app.amber.feature.board.DeepReadTemplateIds
import app.amber.feature.board.TodayBoardSetting
import app.amber.feature.board.hotlist.deepread.template.DeepReadTemplatePackage
import app.amber.feature.board.hotlist.deepread.template.DeepReadRenderedTemplate
import app.amber.feature.board.hotlist.deepread.template.DeepReadTemplateRenderer
import app.amber.core.font.SlidesFontRepository
import app.amber.feature.ui.components.ui.workspaceColors
import app.amber.feature.ui.theme.LocalDarkMode
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
    val darkTheme = LocalDarkMode.current
    val sampleTitle = stringResource(R.string.deep_read_sample_title)
    val templateUnavailableMessage = stringResource(R.string.deep_read_template_unavailable)
    val templatePreviewFailedTemplate = stringResource(
        R.string.deep_read_template_preview_failed,
        "__TEMPLATE_ERROR__",
    )
    val sampleOutput = remember { DeepReadTemplateRenderer.sampleOutput() }
    val selectedTemplateName = when (board.deepReadTemplateId) {
        DeepReadTemplateIds.COMPOSE_MAGAZINE -> stringResource(R.string.deep_read_template_default_magazine)
        DeepReadTemplateIds.EDITORIAL_SLANT -> stringResource(R.string.deep_read_template_editorial_slant)
        else -> customTemplates.firstOrNull { it.id == board.deepReadTemplateId }?.name
            ?: stringResource(R.string.deep_read_template_current)
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
    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = cardShape,
            color = tokens.surface,
            border = androidx.compose.foundation.BorderStroke(1.dp, tokens.line),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.deep_read_template_title), style = LocalAmberType.current.sessionTitle, color = tokens.ink)
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

        TemplateSectionLabel(stringResource(R.string.deep_read_template_default_magazine))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TemplateChip(
                selected = board.deepReadTemplateId == DeepReadTemplateIds.COMPOSE_MAGAZINE,
                label = stringResource(R.string.deep_read_template_default_magazine),
                onClick = { onSelect(DeepReadTemplateIds.COMPOSE_MAGAZINE) },
            )
            TemplateChip(
                selected = board.deepReadTemplateId == DeepReadTemplateIds.EDITORIAL_SLANT,
                label = stringResource(R.string.deep_read_template_editorial_slant),
                onClick = { onSelect(DeepReadTemplateIds.EDITORIAL_SLANT) },
            )
        }
        OutlinedButton(
            onClick = { previewSelectedTemplate() },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(15.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = tokens.accent),
            border = androidx.compose.foundation.BorderStroke(1.dp, tokens.accent.copy(alpha = 0.5f)),
        ) {
            Text(stringResource(R.string.deep_read_template_preview, selectedTemplateName), style = LocalAmberType.current.secondary)
        }

        TemplateSectionLabel(stringResource(R.string.deep_read_template_custom))
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
                                .padding(horizontal = 14.dp, vertical = 8.dp),
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
) {
    val tokens = LocalAmberTokens.current
    Surface(
        shape = androidx.compose.foundation.shape.RoundedCornerShape(50),
        color = if (selected) tokens.accent else tokens.surface2,
        contentColor = if (selected) tokens.accentInk else tokens.ink2,
        border = androidx.compose.foundation.BorderStroke(1.dp, if (selected) tokens.accent else tokens.line2),
        modifier = modifier.clickable { onClick() },
    ) {
        Text(label, modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp), style = LocalAmberType.current.secondary)
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
                    backgroundColor = MaterialTheme.colorScheme.surface,
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
