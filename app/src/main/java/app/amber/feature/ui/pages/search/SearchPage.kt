package app.amber.feature.ui.pages.search

import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.RefreshCw
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import app.amber.agent.R
import app.amber.agent.data.db.fts.MessageSearchResult
import app.amber.agent.data.db.fts.SearchHitSource
import app.amber.core.model.Conversation
import app.amber.feature.ui.components.ds.amberCanvas
import app.amber.feature.ui.components.nav.BackButton
import app.amber.feature.ui.components.ui.WorkspaceSearchField
import app.amber.feature.ui.components.ui.WorkspaceTopBar
import app.amber.feature.ui.context.LocalNavController
import app.amber.feature.ui.theme.LocalAmberTokens
import app.amber.feature.ui.theme.LocalAmberType
import app.amber.core.utils.navigateToChatPage
import app.amber.core.utils.toLocalDateTime
import org.koin.androidx.compose.koinViewModel
import kotlin.uuid.Uuid

private fun SearchFilter.labelRes(): Int = when (this) {
    SearchFilter.ALL -> R.string.search_page_filter_all
    SearchFilter.CONVERSATIONS -> R.string.search_page_filter_conversations
    SearchFilter.MESSAGES -> R.string.search_page_filter_messages
}

@Composable
fun SearchPage(vm: SearchVM = koinViewModel()) {
    val navController = LocalNavController.current
    var showRebuildDialog by remember { mutableStateOf(false) }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    if (showRebuildDialog) {
        AlertDialog(
            onDismissRequest = { showRebuildDialog = false },
            title = { Text(stringResource(R.string.search_page_rebuild_index)) },
            text = { Text(stringResource(R.string.search_page_rebuild_index_desc)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showRebuildDialog = false
                        vm.rebuildIndex()
                    }
                ) {
                    Text(stringResource(R.string.confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showRebuildDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    Scaffold(
        topBar = {
            WorkspaceTopBar(
                navigationIcon = { BackButton() },
                title = stringResource(R.string.search_page_title),
                actions = {
                    IconButton(
                        onClick = { showRebuildDialog = true },
                        enabled = !vm.isRebuilding,
                    ) {
                        Icon(
                            Lucide.RefreshCw,
                            contentDescription = stringResource(R.string.search_page_rebuild_button)
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        modifier = Modifier
            .amberCanvas()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = Color.Transparent,
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding),
        ) {
            WorkspaceSearchField(
                value = vm.searchQuery,
                onValueChange = { vm.onQueryChange(it) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 4.dp),
                placeholder = stringResource(R.string.search_page_placeholder),
                onSubmit = { vm.search() },
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(start = 16.dp, top = 4.dp, end = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SearchFilter.values().forEach { filter ->
                    SearchFilterChip(
                        selected = vm.searchFilter == filter,
                        onClick = { vm.onFilterChange(filter) },
                        label = stringResource(filter.labelRes()),
                    )
                }
            }

            Box(modifier = Modifier.weight(1f)) {
                if (vm.isLoading || vm.isRebuilding) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }

                when {
                    vm.isRebuilding -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            val (current, total) = vm.rebuildProgress
                            Text(
                                text = if (total > 0) stringResource(
                                    R.string.search_page_rebuilding,
                                    current,
                                    total
                                ) else stringResource(R.string.search_page_rebuilding_simple),
                                style = LocalAmberType.current.body,
                                color = LocalAmberTokens.current.ink2,
                            )
                        }
                    }
                    vm.errorKind != null && !vm.hasDisplayedContent -> {
                        SearchErrorState(
                            message = stringResource(vm.errorKind!!.messageRes()),
                            retryLabel = stringResource(R.string.parity_search_retry),
                            onRetry = vm::retryLastOperation,
                        )
                    }
                    vm.searchQuery.isBlank() -> {
                        if (
                            shouldShowRecentConversations(vm.searchQuery, vm.searchFilter) &&
                            vm.recentConversations.isNotEmpty()
                        ) {
                            Column(modifier = Modifier.fillMaxSize()) {
                                if (vm.errorKind != null) {
                                    SearchInlineError(
                                        message = stringResource(vm.errorKind!!.messageRes()),
                                        retryLabel = stringResource(R.string.parity_search_retry),
                                        onRetry = vm::retryLastOperation,
                                    )
                                }
                                LazyColumn(
                                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                                    modifier = Modifier.weight(1f),
                                ) {
                                    item(key = "search_recent_header") {
                                        SearchSectionHeader(
                                            text = stringResource(R.string.search_page_recent_conversations),
                                        )
                                    }
                                    itemsIndexed(
                                        items = vm.recentConversations,
                                        key = { _, conversation -> conversation.id.toString() },
                                    ) { index, conversation ->
                                        RecentConversationItem(
                                            conversation = conversation,
                                            isFirst = index == 0,
                                            isLast = index == vm.recentConversations.lastIndex,
                                            onClick = {
                                                navigateToChatPage(navController, conversation.id)
                                            },
                                        )
                                    }
                                }
                            }
                        } else if (!vm.isLoading) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = if (vm.searchFilter == SearchFilter.MESSAGES) {
                                        stringResource(R.string.search_page_hint)
                                    } else {
                                        stringResource(R.string.parity_search_no_recent_conversations)
                                    },
                                    style = LocalAmberType.current.body,
                                    color = LocalAmberTokens.current.ink3,
                                )
                            }
                        }
                    }

                    vm.visibleResults.isEmpty() && !vm.isLoading -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = stringResource(R.string.search_page_no_results),
                                style = LocalAmberType.current.body,
                                color = LocalAmberTokens.current.ink3,
                            )
                        }
                    }

                    else -> {
                        Column(modifier = Modifier.fillMaxSize()) {
                            if (vm.errorKind != null) {
                                SearchInlineError(
                                    message = stringResource(vm.errorKind!!.messageRes()),
                                    retryLabel = stringResource(R.string.parity_search_retry),
                                    onRetry = vm::retryLastOperation,
                                )
                            }
                            LazyColumn(
                                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                                modifier = Modifier.weight(1f),
                            ) {
                                item(key = "search_results_header") {
                                    SearchSectionHeader(
                                        text = stringResource(
                                            R.string.chat_message_tool_search_results_count,
                                            vm.visibleResults.size,
                                        ),
                                    )
                                }
                                itemsIndexed(
                                    items = vm.visibleResults,
                                    key = { _, result -> result.stableKey() },
                                ) { index, result ->
                                    SearchResultItem(
                                        result = result,
                                        isFirst = index == 0,
                                        isLast = index == vm.visibleResults.lastIndex,
                                        onClick = {
                                            // P8-04: 标题命中（nodeId 为 null）打开会话；
                                            // 正文命中继续跳转具体消息（复用现有跳转）。
                                            val chatId = Uuid.parse(result.conversationId)
                                            if (result.nodeId != null) {
                                                navigateToChatPage(
                                                    navController,
                                                    chatId = chatId,
                                                    nodeId = Uuid.parse(result.nodeId),
                                                )
                                            } else {
                                                navigateToChatPage(navController, chatId = chatId)
                                            }
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun SearchErrorKind.messageRes(): Int = when (this) {
    SearchErrorKind.RECENT_CONVERSATIONS -> R.string.parity_search_recent_error
    SearchErrorKind.MESSAGES -> R.string.parity_search_messages_error
    SearchErrorKind.INDEX_REBUILD -> R.string.parity_search_rebuild_error
}

private fun MessageSearchResult.stableKey(): String = buildString {
    append(conversationId)
    append(':')
    append(nodeId.orEmpty())
    append(':')
    append(messageId.orEmpty())
    append(':')
    append(hitSource.name)
}

@Composable
private fun SearchFilterChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
) {
    val tokens = LocalAmberTokens.current
    val type = LocalAmberType.current
    Surface(
        modifier = Modifier.height(32.dp),
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = if (selected) tokens.accent else Color.Transparent,
        contentColor = if (selected) tokens.accentInk else tokens.ink2,
        border = BorderStroke(1.dp, if (selected) tokens.accent else tokens.line2),
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label,
                style = type.secondary.copy(
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                ),
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun SearchSectionHeader(
    text: String,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalAmberTokens.current
    val type = LocalAmberType.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 28.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("//", style = type.eyebrow, color = tokens.accent)
        Text(text, style = type.eyebrow, color = tokens.ink2)
        Box(
            modifier = Modifier
                .weight(1f)
                .height(1.dp)
                .background(tokens.line),
        )
    }
}

@Composable
private fun SearchErrorState(
    message: String,
    retryLabel: String,
    onRetry: () -> Unit,
) {
    val tokens = LocalAmberTokens.current
    val type = LocalAmberType.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 64.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = message,
            style = type.body,
            color = tokens.ink2,
        )
        TextButton(onClick = onRetry) {
            Text(retryLabel)
        }
    }
}

@Composable
private fun SearchInlineError(
    message: String,
    retryLabel: String,
    onRetry: () -> Unit,
) {
    val tokens = LocalAmberTokens.current
    val type = LocalAmberType.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = message,
            modifier = Modifier
                .weight(1f)
                .padding(end = 8.dp),
            style = type.secondary,
            color = tokens.ink2,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        TextButton(onClick = onRetry) {
            Text(retryLabel)
        }
    }
}

@Composable
private fun RecentConversationItem(
    conversation: Conversation,
    isFirst: Boolean,
    isLast: Boolean,
    onClick: () -> Unit,
) {
    val tokens = LocalAmberTokens.current
    val type = LocalAmberType.current
    val untitled = stringResource(R.string.search_page_untitled)
    val formattedTime = remember(conversation.updateAt) {
        conversation.updateAt.toLocalDateTime()
    }
    val rowShape = RoundedCornerShape(
        topStart = if (isFirst) 14.dp else 0.dp,
        topEnd = if (isFirst) 14.dp else 0.dp,
        bottomStart = if (isLast) 14.dp else 0.dp,
        bottomEnd = if (isLast) 14.dp else 0.dp,
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(rowShape)
            .background(tokens.surface),
    ) {
        Surface(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth(),
            color = Color.Transparent,
        ) {
            Column(
                modifier = Modifier
                    .padding(16.dp)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = conversation.title.ifBlank { untitled },
                    style = type.body.copy(fontWeight = FontWeight.SemiBold),
                    color = tokens.ink,
                )
                if (conversation.lastMessagePreview.isNotBlank()) {
                    Text(
                        text = conversation.lastMessagePreview,
                        style = type.secondary,
                        color = tokens.ink2,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = formattedTime,
                    style = type.meta,
                    color = tokens.ink3,
                )
            }
        }
        if (!isLast) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .height(1.dp)
                    .background(tokens.line),
            )
        }
    }
}

@Composable
private fun SearchResultItem(
    result: MessageSearchResult,
    isFirst: Boolean,
    isLast: Boolean,
    onClick: () -> Unit,
) {
    val tokens = LocalAmberTokens.current
    val type = LocalAmberType.current
    val highlightColor = tokens.accent.copy(alpha = 0.22f)
    val untitled = stringResource(R.string.search_page_untitled)
    val snippetText = buildAnnotatedString {
        val snippet = result.snippet
        var index = 0
        while (index < snippet.length) {
            val start = snippet.indexOf('[', index)
            if (start == -1) {
                append(snippet.substring(index))
                break
            }
            if (start > index) {
                append(snippet.substring(index, start))
            }
            val end = snippet.indexOf(']', start + 1)
            if (end == -1) {
                append(snippet.substring(start))
                break
            }
            val matched = snippet.substring(start + 1, end)
            withStyle(SpanStyle(background = highlightColor)) {
                append(matched)
            }
            index = end + 1
        }
    }
    val formattedTime = remember(result.updateAt) {
        result.updateAt.toLocalDateTime()
    }

    val rowShape = RoundedCornerShape(
        topStart = if (isFirst) 14.dp else 0.dp,
        topEnd = if (isFirst) 14.dp else 0.dp,
        bottomStart = if (isLast) 14.dp else 0.dp,
        bottomEnd = if (isLast) 14.dp else 0.dp,
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(rowShape)
            .background(tokens.surface),
    ) {
        Surface(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth(),
            color = Color.Transparent,
        ) {
            Column(
                modifier = Modifier
                    .padding(16.dp)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // Graphite §3: conversation title is human prose → SANS (sessionTitle), full ink.
                Text(
                    text = result.title.ifBlank { untitled },
                    style = type.body.copy(fontWeight = FontWeight.SemiBold),
                    color = tokens.ink,
                )
                // Graphite §3: snippet/preview → SANS secondary, secondary ink.
                Text(
                    text = snippetText,
                    style = type.secondary,
                    color = tokens.ink2,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                // Graphite §3: timestamp is a machine-fact → MONO (meta), muted ink.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = when {
                            result.titleMatched && result.hitSource == SearchHitSource.BODY ->
                                stringResource(R.string.search_page_hit_title_and_body)
                            result.titleMatched -> stringResource(R.string.search_page_hit_title)
                            else -> stringResource(R.string.search_page_hit_body)
                        },
                        style = type.meta.copy(fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold),
                        color = tokens.ink3,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = formattedTime,
                        style = type.meta,
                        color = tokens.ink3,
                        maxLines = 1,
                    )
                }
            }
        }
        if (!isLast) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .height(1.dp)
                    .background(tokens.line),
            )
        }
    }
}
