package me.rerere.rikkahub.ui.components.message

import android.os.Trace
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastForEachIndexed
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.ui.UIMessageAnnotation
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.R
import me.rerere.rikkahub.BuildConfig
import me.rerere.rikkahub.ui.components.ui.Favicon
import me.rerere.rikkahub.ui.theme.extendColors
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.utils.openUrl
import me.rerere.rikkahub.utils.urlDecode

@Composable
private fun TraceChatComposable(section: String, content: @Composable () -> Unit) {
    if (BuildConfig.DEBUG) {
        Trace.beginSection(section)
    }
    content()
    if (BuildConfig.DEBUG) {
        Trace.endSection()
    }
}

@Composable
internal fun MessageSelectionContainer(
    content: @Composable () -> Unit,
) {
    TraceChatComposable("Amber MessagePartsBlock SelectionContainer") {
        SelectionContainer {
            content()
        }
    }
}

@Composable
internal fun rememberClickCitationHandler(parts: List<UIMessagePart>): (String) -> Unit {
    val context = LocalContext.current
    val partsState by rememberUpdatedState(parts)
    return remember {
        handler@{ citationId ->
            partsState.forEach { part ->
                if (part is UIMessagePart.Tool && part.toolName == "search_web" && part.isExecuted) {
                    val outputText = part.output.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }
                    val items =
                        runCatching { JsonInstant.parseToJsonElement(outputText).jsonObject["items"]?.jsonArray }.getOrNull()
                            ?: return@forEach
                    items.forEach { item ->
                        val id = item.jsonObject["id"]?.jsonPrimitive?.content ?: return@forEach
                        val url = item.jsonObject["url"]?.jsonPrimitive?.content ?: return@forEach
                        if (citationId == id) {
                            context.openUrl(url)
                            return@handler
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun MessageAnnotations(
    annotations: List<UIMessageAnnotation>,
    loading: Boolean,
) {
    if (annotations.isEmpty()) return

    val contentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
    // 2026-05-14: dropped `animateContentSizeIf(loading)` here — see
    // animateContentSizeIf() note in this file. Citations grow during streaming
    // at the same 200ms flush cadence as the body text; nested spring animations
    // across MessageAnnotations + ChainOfThought + Surface + Markdown stacked
    // 4-5 deep and saturated the main thread. Layout still settles correctly
    // without the per-flush animation; final position is unchanged.
    Column {
        var expand by remember { mutableStateOf(false) }
        if (expand) {
            ProvideTextStyle(
                MaterialTheme.typography.labelMedium.copy(
                    color = MaterialTheme.extendColors.gray8.copy(alpha = 0.65f)
                )
            ) {
                Column(
                    modifier = Modifier
                        .drawWithContent {
                            drawContent()
                            drawRoundRect(
                                color = contentColor.copy(alpha = 0.2f),
                                size = Size(width = 10f, height = size.height),
                            )
                        }
                        .padding(start = 16.dp)
                        .padding(4.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    annotations.fastForEachIndexed { index, annotation ->
                        when (annotation) {
                            is UIMessageAnnotation.UrlCitation -> {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Favicon(annotation.url, modifier = Modifier.size(20.dp))
                                    Text(
                                        text = buildAnnotatedString {
                                            append("${index + 1}. ")
                                            withLink(LinkAnnotation.Url(annotation.url)) {
                                                append(annotation.title.urlDecode())
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        TextButton(
            onClick = {
                expand = !expand
            }
        ) {
            Text(stringResource(R.string.citations_count, annotations.size))
        }
    }
}
