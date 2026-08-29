package app.amber.feature.ui.pages.board

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
import androidx.compose.ui.res.stringResource
import app.amber.agent.R
import app.amber.feature.board.DeepReadTemplateIds
import app.amber.feature.board.TodayBoardSetting
import app.amber.feature.board.hotlist.deepread.template.DeepReadTemplatePackage
import app.amber.feature.board.hotlist.deepread.template.DeepReadRenderedTemplate
import app.amber.feature.board.hotlist.deepread.template.DeepReadTemplateRenderer
import app.amber.core.font.SlidesFontRepository
import app.amber.feature.ui.components.ui.workspaceColors
import app.amber.feature.ui.theme.LocalDarkMode

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
    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.deep_read_template_title), style = MaterialTheme.typography.titleSmall)
            TextButton(onClick = onCreateTemplate) {
                Text(stringResource(R.string.deep_read_template_create))
            }
        }
        Text(
            stringResource(R.string.deep_read_template_description),
            style = MaterialTheme.typography.bodySmall,
            color = workspaceColors().muted,
        )
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
            shape = RoundedCornerShape(18.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.primary),
        ) {
            Text(stringResource(R.string.deep_read_template_preview, selectedTemplateName))
        }
        if (customTemplates.isNotEmpty()) {
            Text(stringResource(R.string.deep_read_template_custom), style = MaterialTheme.typography.labelMedium, color = workspaceColors().muted)
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
                        Text(stringResource(R.string.delete))
                    }
                }
            }
        }
        if (invalidTemplateCount > 0) {
            Text(
                stringResource(R.string.deep_read_template_invalid_count, invalidTemplateCount),
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
