package me.rerere.rikkahub.ui.pages.history

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import me.rerere.rikkahub.ui.hooks.pulsePress
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.GlobalSearch
import me.rerere.hugeicons.stroke.Pin
import me.rerere.hugeicons.stroke.BubbleChatQuestion
import me.rerere.hugeicons.stroke.PinOff
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.ui.components.ui.PulsePrimaryButton
import me.rerere.rikkahub.ui.components.ui.RikkaConfirmDialog
import me.rerere.rikkahub.ui.components.ui.WorkspaceBottomSheet
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.utils.navigateToChatPage
import me.rerere.rikkahub.utils.plus
import me.rerere.rikkahub.utils.toRelativeTime
import org.koin.androidx.compose.koinViewModel

/**
 * Conversations home — the primary entry surface for the Pulse app.
 *
 * Replaces what used to be a drawer-only list view with a full-screen
 * page that lands when the user opens Pulse: a sport-orange hero count
 * ("04") under an ALL-CAPS "ACTIVE THREADS · READY" eyebrow, followed by
 * tan modular cards for each conversation. Tapping a row navigates into
 * that specific chat. The bottom-nav's Chats tab routes here.
 *
 * The "active threads" framing intentionally treats every conversation
 * as live signal rather than dead history — Pulse's brand voice is
 * sports-tech / live-data, not archive-of-records. The headline count
 * pulls from the actual list size so the eye lands on a non-zero number
 * the moment the app opens.
 *
 * Top bar gives only secondary actions (search + delete-all); there's
 * no nav-up because this IS the up. Search jumps to the message search
 * screen; delete-all opens a destructive-action dialog.
 */
@Composable
fun HistoryPage(vm: HistoryVM = koinViewModel()) {
    val navController = LocalNavController.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var showDeleteAllDialog by remember { mutableStateOf(false) }
    var longPressTarget by remember { mutableStateOf<Conversation?>(null) }

    val conversations by vm.conversations.collectAsStateWithLifecycle()
    val previews by vm.previews.collectAsStateWithLifecycle()
    val activeCount = conversations.size
    val snackMessageDeleted = stringResource(R.string.history_page_conversation_deleted)
    val snackMessageUndo = stringResource(R.string.history_page_undo)

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
    ) { contentPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding + PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                ConversationsHeader(
                    activeCount = activeCount,
                    onSearchClick = { navController.navigate(Screen.MessageSearch) },
                    onDeleteAllClick = { showDeleteAllDialog = true },
                )
            }
            items(conversations, key = { it.id }) { conversation ->
                SwipeableConversationItem(
                    conversation = conversation,
                    preview = previews[conversation.id] ?: "",
                    onClick = {
                        navigateToChatPage(navController, conversation.id)
                    },
                    onLongClick = { longPressTarget = conversation },
                    onDelete = {
                        scope.launch {
                            val fullConversation = vm.getFullConversation(conversation.id) ?: conversation
                            vm.deleteConversation(conversation)
                            val result = snackbarHostState.showSnackbar(
                                message = snackMessageDeleted,
                                actionLabel = snackMessageUndo,
                                withDismissAction = true,
                            )
                            if (result == SnackbarResult.ActionPerformed) {
                                vm.restoreConversation(fullConversation)
                            }
                        }
                    },
                    onTogglePin = { vm.togglePinStatus(conversation.id) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .animateItem()
                )
            }
            if (conversations.isEmpty()) {
                item {
                    EmptyThreadsCard(
                        onNewChat = { navigateToChatPage(navController) }
                    )
                }
            }
        }
    }

    RikkaConfirmDialog(
        show = showDeleteAllDialog,
        title = stringResource(R.string.history_page_delete_all_conversations),
        confirmText = stringResource(R.string.history_page_delete),
        dismissText = stringResource(R.string.history_page_cancel),
        onConfirm = {
            vm.deleteAllConversations()
            showDeleteAllDialog = false
        },
        onDismiss = { showDeleteAllDialog = false },
        destructive = true,
    ) {
        Text(stringResource(R.string.history_page_delete_all_confirmation))
    }

    longPressTarget?.let { conversation ->
        ConversationActionsSheet(
            conversation = conversation,
            onDismiss = { longPressTarget = null },
            onTogglePin = {
                vm.togglePinStatus(conversation.id)
                longPressTarget = null
            },
            onDelete = {
                scope.launch {
                    val fullConversation = vm.getFullConversation(conversation.id) ?: conversation
                    vm.deleteConversation(conversation)
                    longPressTarget = null
                    val result = snackbarHostState.showSnackbar(
                        message = snackMessageDeleted,
                        actionLabel = snackMessageUndo,
                        withDismissAction = true,
                    )
                    if (result == SnackbarResult.ActionPerformed) {
                        vm.restoreConversation(fullConversation)
                    }
                }
            },
        )
    }
}

@Composable
private fun ConversationsHeader(
    activeCount: Int,
    onSearchClick: () -> Unit,
    onDeleteAllClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // Top row: Pulse brand title + secondary action icons. The icons
        // are circular outlined chips (mockup-faithful) so they pop on
        // the cream surface without competing with the hero count below.
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = (-0.02).em,
                ),
                modifier = Modifier.weight(1f),
            )
            HeaderIconButton(
                icon = HugeIcons.GlobalSearch,
                contentDescription = stringResource(R.string.history_page_search_messages),
                onClick = onSearchClick,
            )
            HeaderIconButton(
                icon = HugeIcons.Delete01,
                contentDescription = stringResource(R.string.history_page_delete_all),
                onClick = onDeleteAllClick,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        // Hero count — the signature Pulse eyebrow + sport-orange numeral
        // pattern. activeCount.toString().padStart(2, '0') so single-digit
        // counts render as "04" / "07" (matching the mockup) instead of
        // dropping the leading zero.
        Text(
            text = stringResource(R.string.history_page_active_threads),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelMedium.copy(
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.10.em,
            ),
            modifier = Modifier.padding(top = 18.dp),
        )
        Text(
            text = activeCount.toString().padStart(2, '0'),
            color = MaterialTheme.colorScheme.secondary,
            style = MaterialTheme.typography.displayLarge.copy(
                fontSize = 64.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = (-0.04).em,
                lineHeight = 64.sp,
            ),
        )
    }
}

@Composable
private fun HeaderIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    Surface(
        modifier = modifier
            .size(36.dp)
            .pulsePress(interactionSource)
            .clip(CircleShape),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.outlineVariant),
        onClick = onClick,
        interactionSource = interactionSource,
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

@Composable
private fun SwipeableConversationItem(
    conversation: Conversation,
    preview: String,
    modifier: Modifier = Modifier,
    onDelete: () -> Unit = {},
    onTogglePin: () -> Unit = {},
    onClick: () -> Unit = {},
    onLongClick: () -> Unit = {},
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
            SwipeToDismissBoxValue.EndToStart -> onDelete()
            else -> {}
        }
    }

    SwipeToDismissBox(
        state = dismissState,
        backgroundContent = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        MaterialTheme.colorScheme.errorContainer,
                        RoundedCornerShape(14.dp),
                    )
                    .padding(horizontal = 20.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Icon(
                    imageVector = HugeIcons.Delete01,
                    contentDescription = stringResource(R.string.history_page_delete),
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        },
        enableDismissFromStartToEnd = false,
        modifier = modifier,
    ) {
        ConversationCard(
            conversation = conversation,
            preview = preview,
            onTogglePin = onTogglePin,
            onClick = onClick,
            onLongClick = onLongClick,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConversationCard(
    conversation: Conversation,
    preview: String,
    modifier: Modifier = Modifier,
    onTogglePin: () -> Unit = {},
    onClick: () -> Unit = {},
    onLongClick: () -> Unit = {},
) {
    val interactionSource = remember { MutableInteractionSource() }
    val haptic = LocalHapticFeedback.current
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = modifier
            .pulsePress(interactionSource)
            .clip(RoundedCornerShape(14.dp))
            .combinedClickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
                onLongClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onLongClick()
                },
            ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = conversation.title.ifBlank { stringResource(R.string.history_page_new_conversation) }
                            .trim(),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                        ),
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (conversation.isPinned) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary),
                        )
                    }
                }
                if (preview.isNotBlank()) {
                    Text(
                        text = preview,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                Text(
                    text = conversation.updateAt.toRelativeTime(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            IconButton(onClick = onTogglePin) {
                Icon(
                    imageVector = if (conversation.isPinned) HugeIcons.PinOff else HugeIcons.Pin,
                    contentDescription = if (conversation.isPinned) {
                        stringResource(R.string.history_page_unpin)
                    } else {
                        stringResource(R.string.history_page_pin)
                    },
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ConversationActionsSheet(
    conversation: Conversation,
    onDismiss: () -> Unit,
    onTogglePin: () -> Unit,
    onDelete: () -> Unit,
) {
    WorkspaceBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Text(
                text = conversation.title.ifBlank { stringResource(R.string.history_page_new_conversation) }.trim(),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(bottom = 16.dp),
            )
            SheetActionRow(
                icon = if (conversation.isPinned) HugeIcons.PinOff else HugeIcons.Pin,
                label = if (conversation.isPinned) {
                    stringResource(R.string.history_page_unpin)
                } else {
                    stringResource(R.string.history_page_pin)
                },
                onClick = onTogglePin,
            )
            SheetActionRow(
                icon = HugeIcons.Delete01,
                label = stringResource(R.string.history_page_delete),
                onClick = onDelete,
                destructive = true,
            )
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun SheetActionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    destructive: Boolean = false,
) {
    val color = if (destructive) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0f),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = color,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = color,
            )
        }
    }
}

@Composable
private fun EmptyThreadsCard(
    onNewChat: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(
            imageVector = HugeIcons.BubbleChatQuestion,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.secondary,
        )
        Text(
            text = stringResource(R.string.history_page_empty_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(R.string.history_page_empty_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        PulsePrimaryButton(
            onClick = onNewChat,
            text = stringResource(R.string.history_page_empty_cta),
        )
    }
}
