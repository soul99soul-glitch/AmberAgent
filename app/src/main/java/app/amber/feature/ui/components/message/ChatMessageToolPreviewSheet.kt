package app.amber.feature.ui.components.message

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.fastForEach
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import app.amber.ai.ui.UIMessagePart
import app.amber.highlight.HighlightText
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Delete01
import app.amber.agent.R
import app.amber.core.repository.MemoryRepository
import app.amber.feature.ui.components.richtext.HighlightCodeBlock
import app.amber.feature.ui.components.richtext.MarkdownBlock
import app.amber.feature.ui.components.richtext.ZoomableAsyncImage
import app.amber.feature.ui.components.ui.Favicon
import app.amber.feature.ui.components.ui.FormItem
import app.amber.core.utils.JsonInstant
import app.amber.core.utils.JsonInstantPretty
import app.amber.core.utils.jsonPrimitiveOrNull
import app.amber.core.utils.openUrl
import org.koin.compose.koinInject

@Composable
internal fun ToolCallPreviewSheet(
    toolName: String,
    arguments: JsonElement,
    content: JsonElement?,
    output: List<UIMessagePart>,
    onDismissRequest: () -> Unit = {}
) {
    val memoryRepo: MemoryRepository = koinInject()
    val scope = rememberCoroutineScope()

    val memoryAction = arguments.getStringContent("action")
    val isMemoryOperation = toolName == ToolNames.MEMORY &&
        memoryAction in listOf(MemoryActions.CREATE, MemoryActions.EDIT)
    val memoryId = (content as? JsonObject)?.get("id")?.jsonPrimitiveOrNull?.intOrNull

    ModalBottomSheet(
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        onDismissRequest = onDismissRequest,
        content = {
            when {
                content == null -> GenericToolPreview(
                    toolName = toolName,
                    arguments = arguments,
                    output = emptyList(),
                    isMemoryOperation = false,
                    memoryId = null,
                    memoryRepo = memoryRepo,
                    scope = scope,
                    onDismissRequest = onDismissRequest
                )

                toolName == ToolNames.SEARCH_WEB -> SearchWebPreview(
                    arguments = arguments,
                    content = content,
                )

                toolName == ToolNames.SCRAPE_WEB -> ScrapeWebPreview(content = content)
                else -> GenericToolPreview(
                    toolName = toolName,
                    arguments = arguments,
                    output = output,
                    isMemoryOperation = isMemoryOperation,
                    memoryId = memoryId,
                    memoryRepo = memoryRepo,
                    scope = scope,
                    onDismissRequest = onDismissRequest
                )
            }
        },
    )
}

@Composable
private fun SearchWebPreview(
    arguments: JsonElement,
    content: JsonElement,
) {
    val context = LocalContext.current
    val items = content.jsonObject["items"]?.jsonArray ?: emptyList()
    val answer = content.getStringContent("answer")
    val query = arguments.getStringContent("query") ?: ""

    LazyColumn(
        modifier = Modifier
            .fillMaxHeight(0.8f)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Text(stringResource(R.string.chat_message_tool_search_prefix, query))
        }

        if (answer != null) {
            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    MarkdownBlock(
                        content = answer,
                        modifier = Modifier
                            .padding(16.dp)
                            .fillMaxWidth(),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        // Top-level image thumbnails (from available_images)
        val topImages = content.jsonObject["available_images"]
            ?.jsonArray
            ?.mapNotNull { it.jsonPrimitive.contentOrNull }
            ?.take(5)
            .orEmpty()
        if (topImages.isNotEmpty()) {
            item {
                Text(
                    text = "相关图片 (${topImages.size})",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                )
            }
            item {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    topImages.forEach { imgUrl ->
                        ZoomableAsyncImage(
                            model = imgUrl,
                            contentDescription = null,
                            modifier = Modifier
                                .height(64.dp)
                                .widthIn(max = 100.dp)
                                .clip(RoundedCornerShape(8.dp)),
                        )
                    }
                }
            }
        }

        if (items.isNotEmpty()) {
            items(items) { item ->
                val url = item.getStringContent("url") ?: return@items
                val title = item.getStringContent("title") ?: return@items
                val text = item.getStringContent("text") ?: return@items
                val itemImages = runCatching {
                    item.jsonObject["images"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }
                }.getOrNull().orEmpty()

                Card(
                    onClick = { context.openUrl(url) },
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp, horizontal = 16.dp),
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Favicon(
                                url = url,
                                modifier = Modifier.size(24.dp)
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(text = title, maxLines = 1)
                                Text(
                                    text = text,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Text(
                                    text = url,
                                    maxLines = 1,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                )
                            }
                        }
                        // Per-item images
                        if (itemImages.isNotEmpty()) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                modifier = Modifier.padding(top = 6.dp),
                            ) {
                                itemImages.take(2).forEach { imgUrl ->
                                    ZoomableAsyncImage(
                                        model = imgUrl,
                                        contentDescription = null,
                                        modifier = Modifier
                                            .height(56.dp)
                                            .widthIn(max = 80.dp)
                                            .clip(RoundedCornerShape(6.dp)),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        } else {
            item {
                HighlightText(
                    code = JsonInstantPretty.encodeToString(content),
                    language = "json",
                    fontSize = 12.sp
                )
            }
        }
    }
}

@Composable
private fun ScrapeWebPreview(content: JsonElement) {
    val urls = content.jsonObject["urls"]?.jsonArray ?: emptyList()

    LazyColumn(
        modifier = Modifier
            .fillMaxHeight(0.8f)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Text(
                text = stringResource(
                    R.string.chat_message_tool_scrape_prefix,
                    urls.joinToString(", ") { it.getStringContent("url") ?: "" }
                )
            )
        }

        items(urls) { url ->
            val urlObject = url.jsonObject
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = urlObject["url"]?.jsonPrimitive?.content ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                    modifier = Modifier.fillMaxWidth()
                )
                Card {
                    MarkdownBlock(
                        content = urlObject["content"]?.jsonPrimitive?.content ?: "",
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(8.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun GenericToolPreview(
    toolName: String,
    arguments: JsonElement,
    output: List<UIMessagePart>,
    isMemoryOperation: Boolean,
    memoryId: Int?,
    memoryRepo: MemoryRepository,
    scope: kotlinx.coroutines.CoroutineScope,
    onDismissRequest: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxHeight(0.8f)
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.chat_message_tool_call_title),
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center
            )

            if (isMemoryOperation && memoryId != null) {
                IconButton(
                    onClick = {
                        scope.launch {
                            memoryRepo.deleteMemory(memoryId)
                            onDismissRequest()
                        }
                    }
                ) {
                    Icon(
                        imageVector = HugeIcons.Delete01,
                        contentDescription = "Delete memory"
                    )
                }
            }
        }
        FormItem(
            label = {
                Text(stringResource(R.string.chat_message_tool_call_label, toolName))
            }
        ) {
            HighlightCodeBlock(
                code = JsonInstantPretty.encodeToString(arguments),
                language = "json",
                style = TextStyle(fontSize = 10.sp, lineHeight = 12.sp),
                forceAutoWrap = true,
            )
        }
        if (output.isNotEmpty()) {
            FormItem(
                label = {
                    Text(stringResource(R.string.chat_message_tool_call_result))
                }
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    output.fastForEach { part ->
                        when (part) {
                            is UIMessagePart.Text -> HighlightCodeBlock(
                                code = runCatching {
                                    JsonInstantPretty.encodeToString(
                                        JsonInstant.parseToJsonElement(part.text)
                                    )
                                }.getOrElse { part.text },
                                language = "json",
                                style = TextStyle(fontSize = 10.sp, lineHeight = 12.sp),
                                forceAutoWrap = true,
                            )

                            is UIMessagePart.Image -> ZoomableAsyncImage(
                                model = part.url,
                                contentDescription = null,
                                modifier = Modifier.fillMaxWidth(),
                            )

                            else -> {}
                        }
                    }
                }
            }
        }
    }
}
