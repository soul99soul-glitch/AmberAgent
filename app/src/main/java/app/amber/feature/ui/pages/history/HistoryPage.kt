package app.amber.feature.ui.pages.history;

import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Pin
import com.composables.icons.lucide.PinOff
import com.composables.icons.lucide.ScanSearch
import com.composables.icons.lucide.Trash2
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxDefaults
import androidx.compose.material3.SwipeToDismissBoxState
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import kotlinx.coroutines.launch
import app.amber.agent.R
import app.amber.agent.Screen
import app.amber.core.model.Conversation
import app.amber.feature.ui.components.nav.BackButton
import app.amber.feature.ui.components.ds.SectionLabel
import app.amber.feature.ui.components.ui.workspaceBorder
import app.amber.feature.ui.components.ui.workspaceColors
import app.amber.feature.ui.components.ui.WorkspaceTopBar
import app.amber.feature.ui.context.LocalNavController
import app.amber.feature.ui.theme.LocalAmberTokens
import app.amber.feature.ui.theme.LocalAmberType
import app.amber.core.utils.navigateToChatPage
import app.amber.core.utils.plus
import app.amber.core.utils.toLocalDateTime
import org.koin.androidx.compose.koinViewModel

@Composable
fun HistoryPage(vm: HistoryVM = koinViewModel()) {
    val navController = LocalNavController.current
    val scope = rememberCoroutineScope()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val snackbarHostState = remember { SnackbarHostState() }
    var showDeleteAllDialog by remember { mutableStateOf(false) }

    val conversations = vm.conversations.collectAsLazyPagingItems()
    val workspace = workspaceColors()
    val hasUpstreamError by vm.hasUpstreamError.collectAsStateWithLifecycle()
    val refreshError = conversations.loadState.refresh as? LoadState.Error
    val appendError = conversations.loadState.append as? LoadState.Error
    val isRefreshLoading = conversations.loadState.refresh is LoadState.Loading

    Scaffold(
        containerColor = workspace.canvas,
        topBar = {
            WorkspaceTopBar(
                title = stringResource(R.string.history_page_title),
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                actions = {
                    IconButton(
                        onClick = {
                            navController.navigate(Screen.MessageSearch)
                        }
                    ) {
                        Icon(
                            Lucide.ScanSearch,
                            contentDescription = stringResource(R.string.history_page_search_messages)
                        )
                    }
                    IconButton(
                        onClick = {
                            showDeleteAllDialog = true
                        }
                    ) {
                        Icon(Lucide.Trash2, contentDescription = stringResource(R.string.history_page_delete_all))
                    }
                }
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        snackbarHost = {
            SnackbarHost(hostState = snackbarHostState)
        }
    ) { contentPadding ->
        val snackMessageDeleted = stringResource(R.string.history_page_conversation_deleted)
        val snackMessageUndo = stringResource(R.string.history_page_undo)
        LazyColumn(
            contentPadding = contentPadding + PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "history_section_label") {
                SectionLabel(
                    text = stringResource(R.string.history_page_title),
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            when {
                hasUpstreamError && conversations.itemCount == 0 -> {
                    item(key = "history_upstream_error") {
                        HistoryErrorState(
                            message = stringResource(R.string.parity_history_load_error),
                            retryLabel = stringResource(R.string.parity_history_retry),
                            onRetry = vm::retryUpstream,
                        )
                    }
                }

                refreshError != null && conversations.itemCount == 0 -> {
                    item(key = "history_refresh_error") {
                        HistoryErrorState(
                            message = stringResource(R.string.parity_history_refresh_error),
                            retryLabel = stringResource(R.string.parity_history_retry),
                            onRetry = { conversations.retry() },
                        )
                    }
                }

                else -> {
                    if (isRefreshLoading && conversations.itemCount == 0) {
                        item(key = "history_refresh_loading") {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                    }

                    // Keep already loaded rows visible if a refresh fails.
                    if (refreshError != null && conversations.itemCount > 0) {
                        item(key = "history_refresh_error_inline") {
                            HistoryInlineError(
                                message = stringResource(R.string.parity_history_refresh_error),
                                retryLabel = stringResource(R.string.parity_history_retry),
                                onRetry = { conversations.retry() },
                            )
                        }
                    }

                    if (hasUpstreamError && conversations.itemCount > 0) {
                        item(key = "history_upstream_error_inline") {
                            HistoryInlineError(
                                message = stringResource(R.string.parity_history_load_error),
                                retryLabel = stringResource(R.string.parity_history_retry),
                                onRetry = vm::retryUpstream,
                            )
                        }
                    }

                    if (!isRefreshLoading && refreshError == null && conversations.itemCount == 0) {
                        item(key = "history_empty") {
                            HistoryEmptyState()
                        }
                    }

                    items(
                        count = conversations.itemCount,
                        key = conversations.itemKey { it.id },
                    ) { index ->
                        val conversation = conversations[index] ?: return@items
                        SwipeableConversationItem(
                            conversation = conversation,
                            onClick = {
                                navigateToChatPage(navController, conversation.id)
                            },
                            onDelete = {
                                scope.launch {
                                    // 先获取完整的对话数据（包含 messageNodes），用于撤销恢复
                                    val fullConversation = vm.getFullConversation(conversation.id) ?: conversation
                                    vm.deleteConversation(conversation)
                                    val result = snackbarHostState.showSnackbar(
                                        message = snackMessageDeleted,
                                        actionLabel = snackMessageUndo,
                                        withDismissAction = true,
                                    )
                                    if (result == SnackbarResult.ActionPerformed) {
                                        vm.restoreConversation(fullConversation)
                                    } else {
                                        vm.purgeDeletedConversation(fullConversation)
                                    }
                                }
                            },
                            onTogglePin = { vm.togglePinStatus(conversation.id) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .animateItem()
                        )
                    }

                    if (appendError != null) {
                        item(key = "history_append_error") {
                            HistoryInlineError(
                                message = stringResource(R.string.parity_history_append_error),
                                retryLabel = stringResource(R.string.parity_history_retry),
                                onRetry = { conversations.retry() },
                            )
                        }
                    }
                }
            }
        }
    }

    if (showDeleteAllDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteAllDialog = false },
            title = { Text(stringResource(R.string.history_page_delete_all_conversations)) },
            text = { Text(stringResource(R.string.history_page_delete_all_confirmation)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.deleteAllConversations()
                        showDeleteAllDialog = false
                    }
                ) {
                    Text(stringResource(R.string.history_page_delete))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showDeleteAllDialog = false }
                ) {
                    Text(stringResource(R.string.history_page_cancel))
                }
            }
        )
    }
}

@Composable
private fun HistoryErrorState(
    message: String,
    retryLabel: String,
    onRetry: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 64.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = message,
            color = workspaceColors().muted,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        TextButton(onClick = onRetry) {
            Text(retryLabel)
        }
    }
}

@Composable
private fun HistoryInlineError(
    message: String,
    retryLabel: String,
    onRetry: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = message,
            modifier = Modifier
                .weight(1f)
                .padding(end = 8.dp),
            color = workspaceColors().muted,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        TextButton(onClick = onRetry) {
            Text(retryLabel)
        }
    }
}

@Composable
private fun HistoryEmptyState() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 64.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.parity_history_empty),
            color = workspaceColors().muted,
        )
    }
}

@Composable
private fun SwipeableConversationItem(
    conversation: Conversation,
    modifier: Modifier = Modifier,
    onDelete: () -> Unit = {},
    onTogglePin: () -> Unit = {},
    onClick: () -> Unit = {},
) {
    val positionThreshold = SwipeToDismissBoxDefaults.positionalThreshold
    val dismissState = remember {
        SwipeToDismissBoxState(
            initialValue = SwipeToDismissBoxValue.Settled,
            positionalThreshold = positionThreshold,
        )
    }

    LaunchedEffect(dismissState.currentValue) {
        when (dismissState.currentValue) {
            SwipeToDismissBoxValue.EndToStart -> {
                onDelete()
            }

            else -> {}
        }
    }

    val workspace = workspaceColors()
    SwipeToDismissBox(
        state = dismissState,
        backgroundContent = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        workspace.redContainer,
                        RoundedCornerShape(14.dp)
                    )
                    .padding(horizontal = 20.dp),
                contentAlignment = Alignment.CenterEnd
            ) {
                Icon(
                    imageVector = Lucide.Trash2,
                    contentDescription = stringResource(R.string.history_page_delete),
                    tint = workspace.red
                )
            }
        },
        enableDismissFromStartToEnd = false,
        modifier = modifier
    ) {
        ConversationItem(
            conversation = conversation,
            onTogglePin = onTogglePin,
            onClick = onClick
        )
    }
}

@Composable
private fun ConversationItem(
    conversation: Conversation,
    modifier: Modifier = Modifier,
    onTogglePin: () -> Unit = {},
    onClick: () -> Unit = {},
) {
    val workspace = workspaceColors()
    val accent = LocalAmberTokens.current.accent
    Surface(
        onClick = onClick,
        color = workspace.paper,
        border = workspaceBorder(),
        shape = RoundedCornerShape(14.dp),
        modifier = modifier
    ) {
        ListItem(
            colors = ListItemDefaults.colors(containerColor = workspace.paper),
            headlineContent = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (conversation.isPinned) {
                        Icon(
                            imageVector = Lucide.Pin,
                            contentDescription = null,
                            tint = accent,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                    // Graphite §3: session title is human prose → SANS (sessionTitle), full ink.
                    Text(
                        text = conversation.title.ifBlank { stringResource(R.string.history_page_new_conversation) }
                            .trim(),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        style = LocalAmberType.current.sessionTitle,
                        color = workspace.ink,
                    )
                }
            },
            supportingContent = {
                // Graphite §3: timestamp is a machine-fact → MONO (meta), muted ink.
                Text(
                    text = conversation.createAt.toLocalDateTime(),
                    style = LocalAmberType.current.meta,
                    color = workspace.muted,
                )
            },
            trailingContent = {
                IconButton(
                    onClick = onTogglePin
                ) {
                    Icon(
                        if (conversation.isPinned) Lucide.PinOff else Lucide.Pin,
                        contentDescription = if (conversation.isPinned) stringResource(R.string.history_page_unpin) else stringResource(
                            R.string.history_page_pin
                        ),
                        tint = if (conversation.isPinned) accent else workspace.muted,
                    )
                }
            }
        )
    }
}
